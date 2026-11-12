package com.vknxworks.shutterctrl.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.vknxworks.shutterctrl.MainActivity
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The intervalometer: N shots every T seconds. A foreground service (type connectedDevice) so it keeps
 * going with the screen off; it runs only while a series is active and stops itself at the end.
 */
class IntervalService : Service() {

    data class State(
        val active: Boolean = false,
        val shots: Int = 0,
        val total: Int = 0,
        val intervalSec: Int = 0,
        val nextAtElapsed: Long = 0,
        val failed: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val total = intent.getIntExtra(EXTRA_SHOTS, 0).coerceIn(1, MAX_SHOTS)
                val interval = intent.getIntExtra(EXTRA_INTERVAL, 0).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
                if (job != null) return START_NOT_STICKY
                createChannel()
                _state.value = State(true, 0, total, interval, SystemClock.elapsedRealtime())
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                job = scope.launch { run(total, interval) }
            }
            else -> if (job == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun run(total: Int, interval: Int) {
        val session = container.session
        var next = SystemClock.elapsedRealtime()
        for (i in 1..total) {
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            val ok = runCatching { session.shoot() }.isSuccess
            next += interval * 1000L
            _state.value = _state.value.copy(shots = i, nextAtElapsed = next, failed = _state.value.failed + if (ok) 0 else 1)
            update()
            // The camera is gone: stop instead of firing into nothing.
            if (!ok && session.link.value !is Link.Connected) break
        }
        finish()
    }

    private fun finish() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        _state.value = _state.value.copy(active = false)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.ch_interval), NotificationManager.IMPORTANCE_LOW)
        ch.description = getString(R.string.ch_interval_desc)
        nm.createNotificationChannel(ch)
    }

    private fun update() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification()) }
    }

    private fun notification(): Notification {
        val s = _state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IntervalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_interval))
            .setContentText(getString(R.string.notif_interval_progress, s.shots, s.total, s.intervalSec))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "interval"
        private const val NOTIF_ID = 7
        const val ACTION_START = "com.vknxworks.shutterctrl.INTERVAL_START"
        const val ACTION_STOP = "com.vknxworks.shutterctrl.INTERVAL_STOP"
        const val EXTRA_SHOTS = "shots"
        const val EXTRA_INTERVAL = "interval"
        const val MAX_SHOTS = 9999
        const val MIN_INTERVAL = 2
        const val MAX_INTERVAL = 3600

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun start(context: Context, shots: Int, intervalSec: Int) {
            context.startForegroundService(
                Intent(context, IntervalService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_SHOTS, shots).putExtra(EXTRA_INTERVAL, intervalSec),
            )
        }

        fun stop(context: Context) {
            if (_state.value.active) context.startService(Intent(context, IntervalService::class.java).setAction(ACTION_STOP))
        }
    }
}
