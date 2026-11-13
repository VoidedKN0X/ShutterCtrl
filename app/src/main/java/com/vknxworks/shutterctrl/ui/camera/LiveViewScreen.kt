package com.vknxworks.shutterctrl.ui.camera

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

    val bitmap by produceState<ImageBitmap?>(null, frame) {
        val f = frame ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(f, 0, f.size)?.asImageBitmap() }.getOrNull() } ?: value
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.live_view))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).background(Color.Black), contentAlignment = Alignment.Center) {
                val b = bitmap
                if (b != null) {
                    Image(b, stringResource(R.string.live_view), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text(stringResource(R.string.live_view_waiting), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            ExposureRow(Modifier.padding(top = 8.dp))
            ShutterButtons()
            FocusStackGroup()
            Text(stringResource(R.string.live_view_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}
