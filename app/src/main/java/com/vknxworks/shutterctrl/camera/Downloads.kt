package com.vknxworks.shutterctrl.camera

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vknxworks.shutterctrl.ptp.ObjectInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Which new photos are downloaded by themselves while you shoot. */
enum class AutoDownload { OFF, JPEG, RAW, ALL;

    fun wants(info: ObjectInfo): Boolean = when (this) {
        OFF -> false
        JPEG -> info.isJpeg
        RAW -> info.isRaw
        ALL -> info.isImage
    }
}

/**
 * The download queue: one file at a time, in the order asked. Used by the Card tab (one file, or a batch) and by
 * auto-download. The state is read by the Card tab: what is done (by handle), what is downloading and how far,
 * and how the current run is going.
 */
class Downloads(private val session: CameraSession, private val scope: CoroutineScope) {
    /** Files downloaded while ShutterCtrl runs, so "Open in LumenCtrl" works without downloading twice. */
    val done = mutableStateMapOf<Int, Uri>()

    /** Files queued or downloading, with how far they are (0..1). */
    val progress = mutableStateMapOf<Int, Float>()

    /** The current run: files finished, failed and queued in all since the queue was last empty. */
    var runDone by mutableIntStateOf(0)
        private set
    var runFailed by mutableIntStateOf(0)
        private set
    var runTotal by mutableIntStateOf(0)
        private set

    /** The last failure's message, for the Card tab. */
    var lastError by mutableStateOf<String?>(null)
        private set

    private class Job(val info: ObjectInfo, val onDone: ((Result<Uri>) -> Unit)?)
    private val queue = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) {
                val h = job.info.handle
                val result = runCatching { session.download(job.info) { f -> scope.launch { if (progress.containsKey(h)) progress[h] = f } } }
                progress.remove(h)
                result.onSuccess { done[h] = it; runDone++ }.onFailure { runFailed++; lastError = it.message.orEmpty() }
                job.onDone?.invoke(result)
                if (progress.isEmpty()) {
                    // The queue is empty: the next file starts a new run.
                    runTotal = 0; runDone = 0; runFailed = 0
                }
            }
        }
    }

    /** Queues [info] unless it's already downloaded or queued. [onDone] is called when it finishes or fails. */
    fun enqueue(info: ObjectInfo, onDone: ((Result<Uri>) -> Unit)? = null): Boolean {
        val h = info.handle
        if (done.containsKey(h) || progress.containsKey(h)) return false
        if (progress.isEmpty()) { runTotal = 0; runDone = 0; runFailed = 0; lastError = null }
        progress[h] = 0f
        runTotal++
        queue.trySend(Job(info, onDone))
        return true
    }

    /** Queues every file of [items] that isn't downloaded or queued yet; returns how many were added. */
    fun enqueueAll(items: List<ObjectInfo>): Int = items.count { enqueue(it) }

    /** A file was deleted from the card: forget it here too (the copy on the phone stays). */
    fun forget(handle: Int) {
        done.remove(handle)
    }
}
