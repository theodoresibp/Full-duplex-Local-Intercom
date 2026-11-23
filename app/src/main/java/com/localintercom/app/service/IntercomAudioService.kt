package com.localintercom.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.localintercom.app.MainActivity

class IntercomAudioService : Service() {
    private lateinit var audioManager: AudioManager
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyScoStarted = false
    private var headsetPttDown = false
    private var audioDeviceCallback: AudioDeviceCallback? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        configureAudioFocus()
        configureMediaSession()
        configureCommunicationRoute()
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                configureCommunicationRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                configureCommunicationRoute()
            }
        }
        audioDeviceCallback = deviceCallback
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun configureAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pttCallback?.invoke(false)
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun configureCommunicationRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            val preferred = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val routed = preferred?.let {
                runCatching { audioManager.setCommunicationDevice(it) }.getOrDefault(false)
            } ?: false
            if (!routed) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        } else {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bluetoothAvailable = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val wiredAvailable = outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (bluetoothAvailable) {
                @Suppress("DEPRECATION")
                runCatching {
                    audioManager.isSpeakerphoneOn = false
                    if (!legacyScoStarted) audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    legacyScoStarted = true
                }
            } else {
                if (legacyScoStarted) {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                    legacyScoStarted = false
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = !wiredAvailable
            }
        }
    }

    private fun configureMediaSession() {
        mediaSession = MediaSession(this, "LocalIntercomMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false

                    if (isCapturingButton()) {
                        captureButton(this@IntercomAudioService, event.keyCode)
                        return true
                    }

                    val pttKey = getMappedKeyCode(this@IntercomAudioService, ACTION_PTT)
                    val nextKey = getMappedKeyCode(this@IntercomAudioService, ACTION_NEXT_CHANNEL)
                    val previousKey = getMappedKeyCode(this@IntercomAudioService, ACTION_PREVIOUS_CHANNEL)

                    if (event.keyCode == nextKey || event.keyCode == previousKey) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            channelStepCallback?.invoke(if (event.keyCode == nextKey) 1 else -1)
                        }
                        return true
                    }

                    if (event.keyCode != pttKey) return super.onMediaButtonEvent(mediaButtonIntent)
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0 && !headsetPttDown) {
                                headsetPttDown = true
                                if (getBluetoothPttMode(this@IntercomAudioService) == BLUETOOTH_PTT_MODE_TOGGLE) {
                                    headsetToggleActive = !headsetToggleActive
                                    pttCallback?.invoke(headsetToggleActive)
                                } else {
                                    pttCallback?.invoke(true)
                                }
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            headsetPttDown = false
                            if (getBluetoothPttMode(this@IntercomAudioService) == BLUETOOTH_PTT_MODE_PRESS_HOLD) {
                                pttCallback?.invoke(false)
                            }
                        }
                    }
                    return true
                }

                override fun onPlay() {
                    if (getBluetoothPttMode(this@IntercomAudioService) == BLUETOOTH_PTT_MODE_TOGGLE) {
                        headsetToggleActive = !headsetToggleActive
                        pttCallback?.invoke(headsetToggleActive)
                    } else {
                        pttCallback?.invoke(true)
                    }
                }

                override fun onPause() {
                    headsetToggleActive = false
                    pttCallback?.invoke(false)
                }

                override fun onStop() {
                    pttCallback?.invoke(false)
                }
            })
            isActive = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Intercom session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the active local intercom session available while the screen is off."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Local Intercom active")
            .setContentText("Listening on the selected channel")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        pttCallback?.invoke(false)
        headsetPttDown = false
        headsetToggleActive = false
        audioDeviceCallback?.let(audioManager::unregisterAudioDeviceCallback)
        audioDeviceCallback = null
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else if (legacyScoStarted) {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.localintercom.app.action.START_AUDIO"
        const val ACTION_STOP = "com.localintercom.app.action.STOP_AUDIO"
        const val BLUETOOTH_PTT_MODE_PRESS_HOLD = "press_hold"
        const val BLUETOOTH_PTT_MODE_TOGGLE = "toggle"
        private const val PREFERENCES_NAME = "intercom_preferences"
        private const val KEY_BLUETOOTH_PTT_MODE = "bluetooth_ptt_mode"
        const val ACTION_PTT = "ptt"
        const val ACTION_NEXT_CHANNEL = "next_channel"
        const val ACTION_PREVIOUS_CHANNEL = "previous_channel"
        private const val KEY_CAPTURE_TARGET = "capture_target"
        private const val KEY_PTT_KEYCODE = "mapped_ptt_keycode"
        private const val KEY_NEXT_KEYCODE = "mapped_next_keycode"
        private const val KEY_PREVIOUS_KEYCODE = "mapped_previous_keycode"
        private const val CHANNEL_ID = "local_intercom_audio"
        private const val NOTIFICATION_ID = 4106
        @Volatile
        private var headsetToggleActive = false

        @Volatile
        private var captureTarget: String? = null
        @Volatile
        var onButtonCaptured: ((String, Int) -> Unit)? = null

        @Volatile
        var pttCallback: ((Boolean) -> Unit)? = null

        @Volatile
        var channelStepCallback: ((Int) -> Unit)? = null

        fun getMappedKeyCode(context: Context, action: String): Int {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val key = when (action) {
                ACTION_PTT -> KEY_PTT_KEYCODE
                ACTION_NEXT_CHANNEL -> KEY_NEXT_KEYCODE
                ACTION_PREVIOUS_CHANNEL -> KEY_PREVIOUS_KEYCODE
                else -> return KeyEvent.KEYCODE_UNKNOWN
            }
            val default = when (action) {
                ACTION_PTT -> KeyEvent.KEYCODE_HEADSETHOOK
                ACTION_NEXT_CHANNEL -> KeyEvent.KEYCODE_MEDIA_NEXT
                ACTION_PREVIOUS_CHANNEL -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_UNKNOWN
            }
            return preferences.getInt(key, default)
        }

        fun setMappedKeyCode(context: Context, action: String, keyCode: Int) {
            require(action in setOf(ACTION_PTT, ACTION_NEXT_CHANNEL, ACTION_PREVIOUS_CHANNEL))
            require(keyCode != KeyEvent.KEYCODE_UNKNOWN)
            val key = when (action) {
                ACTION_PTT -> KEY_PTT_KEYCODE
                ACTION_NEXT_CHANNEL -> KEY_NEXT_KEYCODE
                else -> KEY_PREVIOUS_KEYCODE
            }
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putInt(key, keyCode).apply()
            if (action == ACTION_PTT) {
                headsetToggleActive = false
                pttCallback?.invoke(false)
            }
        }

        fun beginButtonCapture(action: String) {
            require(action in setOf(ACTION_PTT, ACTION_NEXT_CHANNEL, ACTION_PREVIOUS_CHANNEL))
            captureTarget = action
        }

        fun cancelButtonCapture() {
            captureTarget = null
        }

        private fun isCapturingButton(): Boolean = captureTarget != null

        private fun captureButton(context: Context, keyCode: Int) {
            val target = captureTarget ?: return
            captureTarget = null
            setMappedKeyCode(context, target, keyCode)
            onButtonCaptured?.invoke(target, keyCode)
        }

        fun keyCodeLabel(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')

        fun getBluetoothPttMode(context: Context): String =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BLUETOOTH_PTT_MODE, BLUETOOTH_PTT_MODE_PRESS_HOLD)
                ?.takeIf { it == BLUETOOTH_PTT_MODE_TOGGLE }
                ?: BLUETOOTH_PTT_MODE_PRESS_HOLD

        fun setBluetoothPttMode(context: Context, mode: String) {
            require(mode == BLUETOOTH_PTT_MODE_PRESS_HOLD || mode == BLUETOOTH_PTT_MODE_TOGGLE)
            headsetToggleActive = false
            pttCallback?.invoke(false)
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BLUETOOTH_PTT_MODE, mode)
                .apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, IntercomAudioService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IntercomAudioService::class.java).setAction(ACTION_STOP))
        }
    }
}
