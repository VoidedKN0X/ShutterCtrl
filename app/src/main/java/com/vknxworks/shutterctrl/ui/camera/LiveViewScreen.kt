package com.vknxworks.shutterctrl.ui.camera

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vknxworks.shutterctrl.camera.LiveAids
import com.vknxworks.shutterctrl.ptp.MovieRecord
import com.vknxworks.shutterctrl.ptp.Prop
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupRow
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.Link
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ui.components.ScreenTopBar
import com.vknxworks.shutterctrl.ui.nav.LocalNavigator
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live view from the camera (Canon GetViewFinderData JPEG frames), with exposure and shutter controls. */
@Composable
fun LiveViewScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val session = c.session
    val link by session.link.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val frame by session.liveFrame.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        session.startLiveView()
        onDispose {
            // Focus stacking needs live view: leaving it stops a running stack.
            if (session.series.value?.kind == com.vknxworks.shutterctrl.camera.CameraSession.Series.Kind.FOCUS_STACK) session.stopSeries()
            c.appScope.launch { session.stopLiveView() }
        }
    }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(link) { if (link !is Link.Connected) nav.back() }

    // One frame is decoded at a time, into a few bitmaps used in turn instead of a new one per frame. A frame that
    // arrives while the last one is still being decoded is skipped; a frame that can't be decoded keeps the last picture.
    val frames = remember { LiveFrames() }
    val wantHistogram by rememberUpdatedState(settings.liveHistogram)
    var histogram by remember { mutableStateOf<IntArray?>(null) }
    val bitmap by produceState<ImageBitmap?>(null) {
        snapshotFlow { frame }.collect { f ->
            if (f != null) {
                val withHist = wantHistogram
                val decoded = withContext(Dispatchers.Default) {
                    frames.decode(f)?.let { b -> b to if (withHist) histogramOf(b) else null }
                }
                if (decoded != null) {
                    value = decoded.first.asImageBitmap()
                    histogram = decoded.second
                }
            }
        }
    }
    val props by session.props.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var centre by remember { mutableStateOf(0.5f to 0.5f) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.live_view))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
                val b = bitmap
                if (b != null) {
                    // Magnify: the picture on the phone, scaled around the point you tapped.
                    Image(
                        b,
                        stringResource(R.string.live_view),
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom.toFloat()
                                scaleY = zoom.toFloat()
                                transformOrigin = TransformOrigin(centre.first, centre.second)
                            }
                            .pointerInput(zoom) {
                                // A tap picks the point to magnify around (it stays where it is on screen).
                                detectTapGestures { o ->
                                    centre = LiveAids.pictureAt(o.x / size.width, centre.first, zoom) to
                                        LiveAids.pictureAt(o.y / size.height, centre.second, zoom)
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                    if (settings.liveGrid) LiveGrid()
                    histogram?.takeIf { settings.liveHistogram }?.let { LiveHistogram(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
                    if (zoom > 1) Text("×$zoom", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text(stringResource(R.string.live_view_waiting), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            // Live-view aids, on the phone only: nothing is sent to the camera for them.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.liveGrid,
                    onClick = { scope.launch { c.settings.update { it.copy(liveGrid = !it.liveGrid) } } },
                    label = { Text(stringResource(R.string.live_grid)) },
                    leadingIcon = { Icon(Icons.Outlined.Grid3x3, null) },
                )
                FilterChip(
                    selected = settings.liveHistogram,
                    onClick = { scope.launch { c.settings.update { it.copy(liveHistogram = !it.liveHistogram) } } },
                    label = { Text(stringResource(R.string.live_histogram)) },
                    leadingIcon = { Icon(Icons.Outlined.BarChart, null) },
                )
                FilterChip(
                    selected = zoom > 1,
                    onClick = { zoom = LiveAids.nextMagnify(zoom) },
                    label = { Text(if (zoom > 1) stringResource(R.string.live_magnify_on, zoom) else stringResource(R.string.live_magnify)) },
                    leadingIcon = { Icon(Icons.Outlined.ZoomIn, null) },
                )
            }
            ExposureRow(Modifier.padding(top = 8.dp))
            ShutterButtons()
            if (session.canMovie) {
                val recording = props[Prop.EVF_RECORD_STATUS] == MovieRecord.CARD
                Group(stringResource(R.string.movie_title), footer = stringResource(R.string.movie_footer)) {
                    GroupRow(
                        stringResource(if (recording) R.string.movie_stop else R.string.movie_start),
                        stringResource(if (recording) R.string.movie_recording else R.string.movie_start_sub),
                        icon = if (recording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,
                        iconTint = if (recording) CtrlColors.Error else CtrlColors.Text,
                        onClick = {
                            scope.launch {
                                runCatching { session.setMovieRecording(!recording) }.onFailure {
                                    Toast.makeText(context, context.getString(R.string.movie_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
            FocusStackGroup()
            Text(stringResource(R.string.live_view_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}

/**
 * Decodes live-view JPEG frames into [BUFFERS] bitmaps used in turn, so about 15 frames a second don't each need a
 * new bitmap. The bitmap on screen is never the one being written: it comes up for reuse only after the frames after
 * it have been decoded and shown. A frame of another size, or anything else Android can't decode into an old bitmap,
 * gets a fresh one. Not thread-safe: one frame at a time.
 */
private class LiveFrames {
    private val buffers = arrayOfNulls<Bitmap>(BUFFERS)
    private var next = 0

    fun decode(jpeg: ByteArray): Bitmap? {
        val out = decodeInto(jpeg, buffers[next]) ?: decodeInto(jpeg, null) ?: return null
        buffers[next] = out
        next = (next + 1) % BUFFERS
        return out
    }

    private fun decodeInto(jpeg: ByteArray, reuse: Bitmap?): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = 1
            inBitmap = reuse
        }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }.getOrNull()

    private companion object {
        const val BUFFERS = 3
    }
}


/** A brightness histogram of a live-view frame, worked out from a small copy of it. */
private fun histogramOf(b: Bitmap): IntArray? = runCatching {
    val small = Bitmap.createScaledBitmap(b, 160, (160L * b.height / b.width).toInt().coerceAtLeast(1), false)
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    if (small !== b) small.recycle()
    LiveAids.histogram(px)
}.getOrNull()

/** Rule-of-thirds lines over the live view. */
@Composable
private fun LiveGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color(0x66FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.5f)
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5f)
        }
    }
}

/** A small histogram: shadows on the left, highlights on the right; an accent edge where something clips. */
@Composable
private fun LiveHistogram(bins: IntArray, modifier: Modifier = Modifier) {
    val accent = CtrlColors.Accent
    val label = stringResource(R.string.live_histogram)
    Canvas(modifier.size(120.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).semantics { contentDescription = label }) {
        val top = bins.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val peak = kotlin.math.sqrt(top.toFloat())
        val step = size.width / bins.size
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { i, n ->
                val y = size.height - size.height * 0.9f * kotlin.math.sqrt(n.toFloat()) / peak
                lineTo(i * step, y)
                lineTo((i + 1) * step, y)
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Color(0xCCF2F2EE))
        if (bins.first() > top / 8) drawLine(accent, Offset(1f, 0f), Offset(1f, size.height), 3f)
        if (bins.last() > top / 8) drawLine(accent, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 3f)
    }
}
