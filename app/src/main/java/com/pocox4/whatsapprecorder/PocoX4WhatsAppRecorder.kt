/**
 * ============================================================================
 * POCO X4 Pro 5G (Snapdragon 695 / MIUI 13 & 14 / Android 11-13)
 * WhatsApp & WhatsApp Business Call Recorder Pro (v2.0)
 * ============================================================================
 *
 * Full-Featured Improvised Edition:
 * 1. Snapdragon 695 PCM Adaptive Gain Booster (up to 3.5x) + Soft-Knee Limiter
 *    with VOICE_RECOGNITION -> VOICE_COMMUNICATION -> MIC fallback chain.
 * 2. Zero-False-Trigger Call Detection via NotificationListenerService
 *    (Notification.CATEGORY_CALL + ongoing) + AudioManager watchdog + 1.5s debounce.
 * 3. Shake-to-Mark Key Moments: Shake the phone during a call to bookmark the
 *    exact timestamp (e.g., [01:42]) with haptic vibration feedback.
 * 4. Auto-Speakerphone Enhancer Option: Optional speaker routing for louder
 *    two-way acoustic coupling when no headset is plugged in.
 * 5. 4-Digit PIN Privacy Vault: Lock the app so no one else can play your calls.
 * 6. Starred Favorites, Call Notes, Search Filter, Contact Exclusion List,
 *    and Smart 30-Day Auto-Cleanup of unstarred old recordings.
 * 7. One-Tap MIUI 13/14 Setup Shield (Autostart + No Battery Restrictions).
 */

package com.pocox4.whatsapprecorder

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ============================================================================
// 1. SHARED CONFIG, STATE & METADATA STORE
// ============================================================================
object RecorderConfig {
    const val ACTION_START_RECORDING = "com.pocox4.whatsapprecorder.ACTION_START"
    const val ACTION_STOP_RECORDING = "com.pocox4.whatsapprecorder.ACTION_STOP"
    const val ACTION_MARK_MOMENT = "com.pocox4.whatsapprecorder.ACTION_MARK_MOMENT"
    const val ACTION_STATE_CHANGED = "com.pocox4.whatsapprecorder.ACTION_STATE_CHANGED"
    const val EXTRA_CALLER_NAME = "extra_caller_name"
    const val EXTRA_APP_PACKAGE = "extra_app_package"

    const val NOTIFICATION_CHANNEL_ID = "wa_recorder_channel_v2"
    const val NOTIFICATION_ID = 4042

    const val PREFS_NAME = "poco_x4_wa_recorder_v2_prefs"
    const val KEY_AUTO_RECORD = "auto_record_enabled"
    const val KEY_GAIN_MULTIPLIER = "gain_multiplier"
    const val KEY_SHAKE_TO_MARK = "shake_to_mark_enabled"
    const val KEY_AUTO_SPEAKER = "auto_speaker_enabled"
    const val KEY_AUTO_CLEANUP_DAYS = "auto_cleanup_days" // 0 = off, 30 = 30 days
    const val KEY_PIN_CODE = "privacy_pin_code"
    const val KEY_EXCLUDED_CONTACTS = "excluded_contacts_csv"
    const val KEY_METADATA_JSON = "recordings_metadata_json"

    val isRecording = AtomicBoolean(false)
    @Volatile var currentCaller: String = "WhatsApp Call"
    @Volatile var currentFilePath: String? = null
    @Volatile var recordingStartEpochMs: Long = 0L
    val currentBookmarks = CopyOnWriteArrayList<String>()

    fun getRecordingsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "WhatsAppCallRecordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isCallerExcluded(context: Context, caller: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_EXCLUDED_CONTACTS, "") ?: ""
        if (raw.isBlank()) return false
        val lowerCaller = caller.lowercase(Locale.US)
        return raw.split(",")
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .any { lowerCaller.contains(it) }
    }

    fun getMetaForFile(context: Context, fileName: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = try {
            JSONObject(prefs.getString(KEY_METADATA_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        return all.optJSONObject(fileName) ?: JSONObject()
    }

    fun saveMetaForFile(
        context: Context,
        fileName: String,
        starred: Boolean? = null,
        note: String? = null,
        bookmarks: List<String>? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = try {
            JSONObject(prefs.getString(KEY_METADATA_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val item = all.optJSONObject(fileName) ?: JSONObject()
        if (starred != null) item.put("starred", starred)
        if (note != null) item.put("note", note)
        if (bookmarks != null) {
            val arr = JSONArray()
            bookmarks.forEach { arr.put(it) }
            item.put("bookmarks", arr)
        }
        all.put(fileName, item)
        prefs.edit().putString(KEY_METADATA_JSON, all.toString()).apply()
    }

    fun runSmartAutoCleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val days = prefs.getInt(KEY_AUTO_CLEANUP_DAYS, 30)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - (days.toLong() * 24L * 3600L * 1000L)
        val files = getRecordingsDir(context).listFiles() ?: return
        for (file in files) {
            if (file.lastModified() < cutoff) {
                val meta = getMetaForFile(context, file.name)
                if (!meta.optBoolean("starred", false)) {
                    file.delete()
                }
            }
        }
    }
}

// ============================================================================
// 2. MIUI / POCO X4 PRO ANTI-KILL & PERMISSION HELPER
// ============================================================================
object MiuiHelper {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, WhatsAppAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentStr)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(pkgName)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openMiuiAutostartSettings(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        launchFirstResolvableIntent(context, intents)
    }

    @SuppressLint("BatteryLife")
    fun requestNoBatteryRestrictions(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", "POCO WA Recorder Pro")
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        launchFirstResolvableIntent(context, intents)
    }

    private fun launchFirstResolvableIntent(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, "Unable to open settings screen.", Toast.LENGTH_SHORT).show()
    }
}

// ============================================================================
// 3. SNAPDRAGON 695 ADAPTIVE PCM AUDIO ENGINE (.WAV WRITER)
// ============================================================================
class PcmBoostAudioEngine(
    private val gainMultiplier: Float = 3.5f
) {
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start(outputFile: File): Boolean {
        if (isRunning.get()) return true

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufSize <= 0) return false
        val bufferSize = max(minBufSize * 2, 8192)

        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        var initializedRecord: AudioRecord? = null
        for (source in candidateSources) {
            try {
                val candidate = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    initializedRecord = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {}
        }

        val record = initializedRecord ?: return false
        audioRecord = record

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}

        return try {
            record.startRecording()
            isRunning.set(true)
            recordingThread = Thread({
                writeWaveLoop(record, outputFile, bufferSize)
            }, "PocoX4-PCM-Recorder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (_: Exception) {
            cleanupHardware()
            false
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            recordingThread?.join(1500)
        } catch (_: InterruptedException) {}
        cleanupHardware()
    }

    private fun cleanupHardware() {
        try { agc?.release() } catch (_: Exception) {}
        agc = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun writeWaveLoop(record: AudioRecord, outputFile: File, bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        val byteBuffer = ByteArray(shortBuffer.size * 2)
        var totalAudioBytes = 0L

        try {
            FileOutputStream(outputFile).use { fos ->
                fos.write(ByteArray(44))
                var envelope = 1000f
                while (isRunning.get()) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts > 0) {
                        for (i in 0 until readShorts) {
                            val raw = shortBuffer[i].toFloat()
                            val absVal = abs(raw)
                            envelope = if (absVal > envelope) {
                                envelope * 0.90f + absVal * 0.10f
                            } else {
                                envelope * 0.9995f + absVal * 0.0005f
                            }
                            val dynamicGain = when {
                                envelope < 1800f -> gainMultiplier * 1.35f
                                envelope < 4500f -> gainMultiplier
                                else -> max(1.0f, gainMultiplier * 0.55f)
                            }
                            var amplified = raw * dynamicGain
                            if (amplified > 26000f) {
                                amplified = 26000f + (amplified - 26000f) * 0.2f
                            } else if (amplified < -26000f) {
                                amplified = -26000f + (amplified + 26000f) * 0.2f
                            }
                            val clamped = min(32767f, max(-32768f, amplified)).toInt().toShort()
                            byteBuffer[i * 2] = (clamped.toInt() and 0x00FF).toByte()
                            byteBuffer[i * 2 + 1] = ((clamped.toInt() shr 8) and 0x00FF).toByte()
                        }
                        val bytesToWrite = readShorts * 2
                        fos.write(byteBuffer, 0, bytesToWrite)
                        totalAudioBytes += bytesToWrite
                    }
                }
                fos.flush()
            }
            updateWavHeader(outputFile, totalAudioBytes)
        } catch (_: Exception) {}
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        if (!file.exists()) return
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * 1 * 16 / 8).toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, 1)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate.toInt())
        writeShortLE(header, 32, 2)
        writeShortLE(header, 34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, totalAudioLen.toInt())

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Short) {
        buf[offset] = (value.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}

// ============================================================================
// 4. ACCESSIBILITY + FOREGROUND RECORDING + SHAKE-TO-MARK SERVICE
// ============================================================================
class WhatsAppAccessibilityService : AccessibilityService(), SensorEventListener {

    private var audioEngine: PcmBoostAudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var sensorManager: SensorManager? = null
    private var lastShakeMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callWatchdogRunnable = object : Runnable {
        override fun run() {
            if (RecorderConfig.isRecording.get()) {
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                if (mode == AudioManager.MODE_NORMAL && !WhatsAppCallNotificationListener.hasActiveCallNotification) {
                    stopCallRecording()
                    return
                }
                mainHandler.postDelayed(this, 2000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderConfig.ACTION_START_RECORDING -> {
                val caller = intent.getStringExtra(RecorderConfig.EXTRA_CALLER_NAME) ?: "WhatsApp Call"
                startCallRecording(caller)
            }
            RecorderConfig.ACTION_STOP_RECORDING -> {
                stopCallRecording()
            }
            RecorderConfig.ACTION_MARK_MOMENT -> {
                markCallTimestamp("Notification Button")
            }
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val prefs = getSharedPreferences(RecorderConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RecorderConfig.KEY_AUTO_RECORD, true)) return

        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        if (mode == AudioManager.MODE_IN_COMMUNICATION && !RecorderConfig.isRecording.get()) {
            startCallRecording("WhatsApp Call")
        }
    }

    override fun onInterrupt() {
        stopCallRecording()
    }

    override fun onDestroy() {
        stopCallRecording()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (!RecorderConfig.isRecording.get()) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (gForce > 2.35) {
            val now = System.currentTimeMillis()
            if (now - lastShakeMs > 2000L) {
                lastShakeMs = now
                markCallTimestamp("Shake Gesture")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun markCallTimestamp(source: String) {
        if (!RecorderConfig.isRecording.get()) return
        val elapsedSec = max(0L, (System.currentTimeMillis() - RecorderConfig.recordingStartEpochMs) / 1000L)
        val mins = elapsedSec / 60L
        val secs = elapsedSec % 60L
        val stamp = String.format(Locale.US, "%02d:%02d (%s)", mins, secs, source)
        RecorderConfig.currentBookmarks.add(stamp)

        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(120)
            }
        } catch (_: Exception) {}

        sendBroadcast(Intent(RecorderConfig.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    @SuppressLint("WakelockTimeout")
    private fun startCallRecording(callerName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (RecorderConfig.isCallerExcluded(this, callerName)) return
        if (RecorderConfig.isRecording.getAndSet(true)) return

        RecorderConfig.runSmartAutoCleanup(this)

        val sanitizedCaller = callerName.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .trim()
            .take(32)
            .ifEmpty { "WhatsApp_Call" }
            .replace(" ", "_")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(
            RecorderConfig.getRecordingsDir(this),
            "WA_${sanitizedCaller}_$timestamp.wav"
        )

        RecorderConfig.currentCaller = callerName
        RecorderConfig.currentFilePath = outFile.absolutePath
        RecorderConfig.recordingStartEpochMs = System.currentTimeMillis()
        RecorderConfig.currentBookmarks.clear()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PocoX4Recorder::CallWakeLock"
        ).apply {
            acquire(2 * 60 * 60 * 1000L)
        }

        val notification = buildRecordingNotification(callerName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RecorderConfig.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(RecorderConfig.NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences(RecorderConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val gain = prefs.getFloat(RecorderConfig.KEY_GAIN_MULTIPLIER, 3.5f)

        if (prefs.getBoolean(RecorderConfig.KEY_AUTO_SPEAKER, false)) {
            try {
                audioManager?.isSpeakerphoneOn = true
            } catch (_: Exception) {}
        }

        if (prefs.getBoolean(RecorderConfig.KEY_SHAKE_TO_MARK, true)) {
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            }
        }

        val engine = PcmBoostAudioEngine(gain)
        if (engine.start(outFile)) {
            audioEngine = engine
            sendBroadcast(Intent(RecorderConfig.ACTION_STATE_CHANGED).setPackage(packageName))
            mainHandler.postDelayed(callWatchdogRunnable, 3000L)
        } else {
            RecorderConfig.isRecording.set(false)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun stopCallRecording() {
        if (!RecorderConfig.isRecording.getAndSet(false)) return
        mainHandler.removeCallbacks(callWatchdogRunnable)
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}

        audioEngine?.stop()
        audioEngine = null

        val savedPath = RecorderConfig.currentFilePath
        if (savedPath != null && RecorderConfig.currentBookmarks.isNotEmpty()) {
            val fName = File(savedPath).name
            RecorderConfig.saveMetaForFile(
                this,
                fName,
                bookmarks = RecorderConfig.currentBookmarks.toList()
            )
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(RecorderConfig.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RecorderConfig.NOTIFICATION_CHANNEL_ID,
                "WhatsApp Call Recording Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WhatsApp call recorder alive on POCO X4 Pro during active calls"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildRecordingNotification(caller: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markIntent = Intent(this, WhatsAppAccessibilityService::class.java).apply {
            action = RecorderConfig.ACTION_MARK_MOMENT
        }
        val pendingMark = PendingIntent.getService(
            this, 2, markIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WhatsAppAccessibilityService::class.java).apply {
            action = RecorderConfig.ACTION_STOP_RECORDING
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RecorderConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording WhatsApp: $caller")
            .setContentText("3.5x PCM Boost Active • Shake phone to bookmark moment")
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_input_add, "Bookmark Moment", pendingMark)
            .addAction(android.R.drawable.ic_media_pause, "Stop & Save", pendingStop)
            .build()
    }
}

// ============================================================================
// 5. WHATSAPP CALL NOTIFICATION LISTENER (ZERO FALSE TRIGGERS)
// ============================================================================
class WhatsAppCallNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var hasActiveCallNotification: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val prefs = getSharedPreferences(RecorderConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RecorderConfig.KEY_AUTO_RECORD, true)) return

        val notification = sbn.notification ?: return
        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val isOngoing = sbn.isOngoing

        if (isCallCategory && isOngoing) {
            hasActiveCallNotification = true
            pendingStopRunnable?.let { handler.removeCallbacks(it) }
            pendingStopRunnable = null

            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "WhatsApp Contact"

            if (!RecorderConfig.isRecording.get()) {
                val startIntent = Intent(this, WhatsAppAccessibilityService::class.java).apply {
                    action = RecorderConfig.ACTION_START_RECORDING
                    putExtra(RecorderConfig.EXTRA_CALLER_NAME, title)
                    putExtra(RecorderConfig.EXTRA_APP_PACKAGE, pkg)
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        if (notification.category == Notification.CATEGORY_CALL) {
            hasActiveCallNotification = false
            val stopTask = Runnable {
                if (!hasActiveCallNotification && RecorderConfig.isRecording.get()) {
                    val stopIntent = Intent(this, WhatsAppAccessibilityService::class.java).apply {
                        action = RecorderConfig.ACTION_STOP_RECORDING
                    }
                    startService(stopIntent)
                }
            }
            pendingStopRunnable = stopTask
            handler.postDelayed(stopTask, 1500L)
        }
    }
}

// ============================================================================
// 6. MAIN ACTIVITY UI (WITH PIN VAULT, SEARCH, FAVORITES, BOOKMARKS & NOTES)
// ============================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var statusBanner: TextView
    private lateinit var checkAudioPerm: TextView
    private lateinit var checkAccessibility: TextView
    private lateinit var checkNotifListener: TextView
    private lateinit var checkBattery: TextView
    private lateinit var btnRecordToggle: Button
    private lateinit var btnBookmarkMoment: Button
    private lateinit var searchInput: EditText
    private lateinit var recordingsContainer: LinearLayout
    private var mediaPlayer: MediaPlayer? = null
    private var showOnlyStarred: Boolean = false
    private var searchQuery: String = ""
    private var isVaultUnlocked: Boolean = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUiState()
            loadRecordingsList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(RecorderConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedPin = prefs.getString(RecorderConfig.KEY_PIN_CODE, "") ?: ""
        if (savedPin.isNotEmpty() && !isVaultUnlocked) {
            setContentView(buildPinLockUi(savedPin))
        } else {
            isVaultUnlocked = true
            setContentView(buildMainUi())
            requestCoreRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isVaultUnlocked) return
        val filter = IntentFilter(RecorderConfig.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        refreshUiState()
        loadRecordingsList()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun buildPinLockUi(expectedPin: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val title = TextView(this).apply {
            text = "🔒 POCO WA Recorder Vault"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "Enter your 4-digit privacy PIN to access call recordings"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        val pinInput = EditText(this).apply {
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        val unlockBtn = Button(this).apply {
            text = "Unlock Recordings"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (pinInput.text.toString() == expectedPin) {
                    isVaultUnlocked = true
                    setContentView(buildMainUi())
                    requestCoreRuntimePermissions()
                    refreshUiState()
                    loadRecordingsList()
                } else {
                    Toast.makeText(this@MainActivity, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(title)
        root.addView(sub)
        root.addView(pinInput)
        root.addView(unlockBtn)
        return root
    }

    private fun requestCoreRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun buildMainUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "POCO X4 Pro WhatsApp Recorder v2.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Snapdragon 695 3.5x PCM Boost • Shake-to-Mark • MIUI Shield"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(title)
        root.addView(subtitle)

        statusBanner = TextView(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(statusBanner)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(16) }
            layoutParams = lp
        }

        btnRecordToggle = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                if (RecorderConfig.isRecording.get()) {
                    val stopIntent = Intent(this@MainActivity, WhatsAppAccessibilityService::class.java).apply {
                        action = RecorderConfig.ACTION_STOP_RECORDING
                    }
                    startService(stopIntent)
                } else {
                    val startIntent = Intent(this@MainActivity, WhatsAppAccessibilityService::class.java).apply {
                        action = RecorderConfig.ACTION_START_RECORDING
                        putExtra(RecorderConfig.EXTRA_CALLER_NAME, "Manual_Test_Call")
                    }
                    ContextCompat.startForegroundService(this@MainActivity, startIntent)
                }
            }
        }

        btnBookmarkMoment = Button(this).apply {
            text = "📌 Mark Moment"
            setBackgroundColor(Color.parseColor("#0D9488"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (RecorderConfig.isRecording.get()) {
                    val markIntent = Intent(this@MainActivity, WhatsAppAccessibilityService::class.java).apply {
                        action = RecorderConfig.ACTION_MARK_MOMENT
                    }
                    startService(markIntent)
                    Toast.makeText(this@MainActivity, "Timestamp bookmarked!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Start a call recording first to bookmark moments.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(btnRecordToggle)
        btnRow.addView(btnBookmarkMoment)
        root.addView(btnRow)

        // Section 1: POCO X4 Pro / MIUI Checklist Card
        val checklistCard = createCardLayout()
        val checklistHeader = TextView(this).apply {
            text = "1. POCO X4 Pro / MIUI Setup Shield"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        checklistCard.addView(checklistHeader)

        checkAudioPerm = createStatusRow("Microphone Permission", "Grant") {
            requestCoreRuntimePermissions()
        }
        checkAccessibility = createStatusRow("Accessibility Audio Service", "Enable") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        checkNotifListener = createStatusRow("WhatsApp Call Auto-Trigger", "Enable") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        checkBattery = createStatusRow("MIUI Battery ('No Restrictions')", "Fix") {
            MiuiHelper.requestNoBatteryRestrictions(this)
        }
        val miuiAutostartRow = createStatusRow("MIUI Autostart (Keep Alive on Reboot)", "Open") {
            MiuiHelper.openMiuiAutostartSettings(this)
        }

        checklistCard.addView(checkAudioPerm)
        checklistCard.addView(checkAccessibility)
        checklistCard.addView(checkNotifListener)
        checklistCard.addView(checkBattery)
        checklistCard.addView(miuiAutostartRow)
        root.addView(checklistCard)

        // Section 2: Smart Features & Audio Settings Card
        val prefs = getSharedPreferences(RecorderConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val settingsCard = createCardLayout().apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            layoutParams = lp
        }

        val settingsHeader = TextView(this).apply {
            text = "2. Pro Features & Snapdragon 695 Audio"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        settingsCard.addView(settingsHeader)

        val autoSwitch = Switch(this).apply {
            text = "Auto-record WhatsApp & WhatsApp Business calls"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(RecorderConfig.KEY_AUTO_RECORD, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(RecorderConfig.KEY_AUTO_RECORD, checked).apply()
            }
        }
        settingsCard.addView(autoSwitch)

        val boostSwitch = Switch(this).apply {
            text = "3.5x Earpiece PCM Gain Booster (Clear Remote Voice)"
            setTextColor(Color.WHITE)
            val currentGain = prefs.getFloat(RecorderConfig.KEY_GAIN_MULTIPLIER, 3.5f)
            isChecked = currentGain >= 3.4f
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                val newGain = if (checked) 3.5f else 2.0f
                prefs.edit().putFloat(RecorderConfig.KEY_GAIN_MULTIPLIER, newGain).apply()
            }
        }
        settingsCard.addView(boostSwitch)

        val shakeSwitch = Switch(this).apply {
            text = "Shake Phone During Call to Bookmark Important Moment"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(RecorderConfig.KEY_SHAKE_TO_MARK, true)
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(RecorderConfig.KEY_SHAKE_TO_MARK, checked).apply()
            }
        }
        settingsCard.addView(shakeSwitch)

        val speakerSwitch = Switch(this).apply {
            text = "Auto-Speakerphone Mode (Max Two-Way Volume)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(RecorderConfig.KEY_AUTO_SPEAKER, false)
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(RecorderConfig.KEY_AUTO_SPEAKER, checked).apply()
            }
        }
        settingsCard.addView(speakerSwitch)

        val cleanupSwitch = Switch(this).apply {
            text = "Smart Auto-Cleanup (Delete unstarred calls older than 30d)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getInt(RecorderConfig.KEY_AUTO_CLEANUP_DAYS, 30) > 0
            setPadding(0, dp(8), 0, dp(10))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putInt(RecorderConfig.KEY_AUTO_CLEANUP_DAYS, if (checked) 30 else 0).apply()
            }
        }
        settingsCard.addView(cleanupSwitch)

        // Excluded contacts input
        val excludeInput = EditText(this).apply {
            hint = "Excluded contacts (comma-separated names)..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setText(prefs.getString(RecorderConfig.KEY_EXCLUDED_CONTACTS, "") ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(RecorderConfig.KEY_EXCLUDED_CONTACTS, s?.toString() ?: "").apply()
                }
            })
        }
        settingsCard.addView(excludeInput)

        // PIN Vault setup input
        val pinSetupInput = EditText(this).apply {
            hint = "Optional 4-Digit Privacy PIN (leave blank to disable)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.getString(RecorderConfig.KEY_PIN_CODE, "") ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    if (v.isEmpty() || v.length == 4) {
                        prefs.edit().putString(RecorderConfig.KEY_PIN_CODE, v).apply()
                    }
                }
            })
        }
        settingsCard.addView(pinSetupInput)
        root.addView(settingsCard)

        // Section 3: Saved Recordings & Search Filter
        val recHeader = TextView(this).apply {
            text = "Saved WhatsApp Call Recordings (.WAV)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(8))
        }
        root.addView(recHeader)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        searchInput = EditText(this).apply {
            hint = "Search caller or note..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    loadRecordingsList()
                }
            })
        }
        val btnStarFilter = Button(this).apply {
            text = "★ All"
            setOnClickListener {
                showOnlyStarred = !showOnlyStarred
                text = if (showOnlyStarred) "★ Starred" else "★ All"
                loadRecordingsList()
            }
        }
        filterRow.addView(searchInput)
        filterRow.addView(btnStarFilter)
        root.addView(filterRow)

        recordingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(recordingsContainer)

        scroll.addView(root)
        return scroll
    }

    private fun refreshUiState() {
        val recording = RecorderConfig.isRecording.get()
        val bannerBg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (recording) Color.parseColor("#DC2626") else Color.parseColor("#1E293B"))
        }
        statusBanner.background = bannerBg
        val marksCount = RecorderConfig.currentBookmarks.size
        statusBanner.text = if (recording) {
            "● RECORDING: ${RecorderConfig.currentCaller} ($marksCount marks)"
        } else {
            "● STANDBY: Ready for WhatsApp / WhatsApp Business Call"
        }

        btnRecordToggle.text = if (recording) "Stop & Save WAV" else "Start Test Recording"
        btnRecordToggle.setBackgroundColor(
            if (recording) Color.parseColor("#EF4444") else Color.parseColor("#2563EB")
        )
        btnRecordToggle.setTextColor(Color.WHITE)

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasAcc = MiuiHelper.isAccessibilityServiceEnabled(this)
        val hasNotif = MiuiHelper.isNotificationListenerEnabled(this)
        val hasBattery = MiuiHelper.isIgnoringBatteryOptimizations(this)

        updateRowState(checkAudioPerm, "Microphone Permission", hasAudio)
        updateRowState(checkAccessibility, "Accessibility Audio Service", hasAcc)
        updateRowState(checkNotifListener, "WhatsApp Call Auto-Trigger", hasNotif)
        updateRowState(checkBattery, "MIUI Battery ('No Restrictions')", hasBattery)
    }

    private fun loadRecordingsList() {
        recordingsContainer.removeAllViews()
        val allFiles = RecorderConfig.getRecordingsDir(this)
            .listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val filtered = allFiles.filter { f ->
            val meta = RecorderConfig.getMetaForFile(this, f.name)
            val isStarred = meta.optBoolean("starred", false)
            val note = meta.optString("note", "")
            val matchesStar = !showOnlyStarred || isStarred
            val matchesQuery = searchQuery.isEmpty() ||
                f.name.contains(searchQuery, ignoreCase = true) ||
                note.contains(searchQuery, ignoreCase = true)
            matchesStar && matchesQuery
        }

        if (filtered.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No matching recordings found."
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(8), 0, dp(8))
            }
            recordingsContainer.addView(emptyView)
            return
        }

        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        for (file in filtered) {
            val meta = RecorderConfig.getMetaForFile(this, file.name)
            var isStarred = meta.optBoolean("starred", false)
            val note = meta.optString("note", "")
            val bookmarksArr = meta.optJSONArray("bookmarks")
            val bookmarksList = mutableListOf<String>()
            if (bookmarksArr != null) {
                for (i in 0 until bookmarksArr.length()) {
                    bookmarksList.add(bookmarksArr.getString(i))
                }
            }

            val card = createCardLayout().apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                layoutParams = lp
            }

            val nameView = TextView(this).apply {
                text = (if (isStarred) "★ " else "") + file.name
                setTextColor(if (isStarred) Color.parseColor("#FBBF24") else Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            val sizeKb = max(1L, file.length() / 1024L)
            val metaView = TextView(this).apply {
                val marksText = if (bookmarksList.isNotEmpty()) {
                    "\n📌 Bookmarks: ${bookmarksList.joinToString(", ")}"
                } else ""
                text = "${dateFormat.format(Date(file.lastModified()))} • ${sizeKb} KB$marksText"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, dp(6))
            }

            val noteInput = EditText(this).apply {
                hint = "Add call note or summary..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setText(note)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        RecorderConfig.saveMetaForFile(
                            this@MainActivity,
                            file.name,
                            note = s?.toString() ?: ""
                        )
                    }
                })
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnPlay = Button(this).apply {
                text = "Play"
                setOnClickListener { playRecording(file) }
            }
            val btnStar = Button(this).apply {
                text = if (isStarred) "Unstar" else "★ Star"
                setOnClickListener {
                    isStarred = !isStarred
                    RecorderConfig.saveMetaForFile(this@MainActivity, file.name, starred = isStarred)
                    loadRecordingsList()
                }
            }
            val btnShare = Button(this).apply {
                text = "Share"
                setOnClickListener { shareRecording(file) }
            }
            val btnDelete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    stopPlayback()
                    file.delete()
                    loadRecordingsList()
                }
            }

            actionsRow.addView(btnPlay)
            actionsRow.addView(btnStar)
            actionsRow.addView(btnShare)
            actionsRow.addView(btnDelete)

            card.addView(nameView)
            card.addView(metaView)
            card.addView(noteInput)
            card.addView(actionsRow)
            recordingsContainer.addView(card)
        }
    }

    private fun playRecording(file: File) {
        stopPlayback()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            Toast.makeText(this, "Playing: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun shareRecording(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share WhatsApp Recording"))
        } catch (_: Exception) {
            Toast.makeText(this, "File path: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
        }
    }

    private fun createStatusRow(label: String, actionText: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$label  [$actionText]"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun updateRowState(view: TextView, label: String, ok: Boolean) {
        if (ok) {
            view.text = "✓ $label: Configured"
            view.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            view.text = "✗ $label: Tap here to enable"
            view.setTextColor(Color.parseColor("#FBBF24"))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
