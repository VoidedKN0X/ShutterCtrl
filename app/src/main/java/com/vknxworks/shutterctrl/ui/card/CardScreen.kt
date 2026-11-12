package com.vknxworks.shutterctrl.ui.card

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.Link
import com.vknxworks.shutterctrl.camera.Saver
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ptp.ObjectInfo
import com.vknxworks.shutterctrl.ui.common.launch
import com.vknxworks.shutterctrl.ui.common.sizeText
import com.vknxworks.shutterctrl.ui.components.CtrlBottomSheet
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlProgressBar
import com.vknxworks.shutterctrl.ui.components.CtrlSquareButton
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.components.MessageView
import com.vknxworks.shutterctrl.ui.components.TabTitle
import com.vknxworks.shutterctrl.ui.components.bottomClearance
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.CtrlType
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What was downloaded in this session, so "Open in LumenCtrl" works without downloading twice. */
private val downloaded = mutableStateMapOf<Int, Uri>()
private val progress = mutableStateMapOf<Int, Float>()

/** The Card tab: the camera's card as thumbnails; download JPEG and RAW files to this phone. */
@Composable
fun CardScreen() {
    val c = LocalContext.current.container
    val link by c.session.link.collectAsStateWithLifecycle()
    val card by c.session.card.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<ObjectInfo?>(null) }
    val connected = link is Link.Connected
    LaunchedEffect(connected) { if (connected && !card.loaded && !card.loading) c.session.loadCard() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_card)) {
            if (connected) CtrlSquareButton(Icons.Outlined.Refresh, stringResource(R.string.refresh), { c.session.loadCard() }, enabled = !card.loading)
        }
        when {
            !connected -> MessageView(Icons.Outlined.LinkOff, stringResource(R.string.card_not_connected), stringResource(R.string.card_not_connected_body))
            card.error != null -> MessageView(Icons.Outlined.SdCard, stringResource(R.string.card_error), card.error, actionLabel = stringResource(R.string.retry), onAction = { c.session.loadCard() })
            card.items.isEmpty() && card.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CtrlColors.AccentText) }
            card.items.isEmpty() -> MessageView(Icons.Outlined.SdCard, stringResource(R.string.card_empty), stringResource(R.string.card_empty_body))
            else -> {
                if (card.loading) CtrlProgressBar(0.5f, Modifier.padding(horizontal = Gutter), color = CtrlColors.Accent)
                LazyVerticalGrid(
                    GridCells.Adaptive(108.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(card.items, key = { it.handle }) { info -> Tile(info) { open = info } }
                }
            }
        }
    }
    open?.let { info -> FileSheet(info) { open = null } }
}

@Composable
private fun Tile(info: ObjectInfo, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val bitmap by produceState<ImageBitmap?>(null, info.handle) {
        val bytes = c.session.thumbnail(info) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.aspectRatio(1f).clip(shape).background(CtrlColors.Surface).clickable(onClick = onClick)) {
        val b = bitmap
        if (b != null) Image(b, info.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, info.fileName, Modifier.align(Alignment.Center).size(28.dp), tint = CtrlColors.Muted)
        Text(
            info.extension.ifBlank { "?" },
            style = CtrlType.Status,
            color = CtrlColors.OnAccent,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (info.isRaw) CtrlColors.Accent else CtrlColors.Accent.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        if (downloaded.containsKey(info.handle)) {
            Icon(Icons.Outlined.Download, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp), tint = CtrlColors.AccentText)
        }
    }
}

@Composable
private fun FileSheet(info: ObjectInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val lumen = remember { Saver.lumenInstalled(context) }
    val p = progress[info.handle]
    val uri = downloaded[info.handle]

    fun download(thenOpen: Boolean) {
        progress[info.handle] = 0f
        c.appScope.launch {
            runCatching { c.session.download(info) { f -> c.appScope.launch { progress[info.handle] = f } } }
                .onSuccess { u ->
                    downloaded[info.handle] = u
                    val inDownloads = u.toString().contains("/downloads/")
                    Toast.makeText(context, context.getString(if (inDownloads) R.string.downloaded_dl else R.string.downloaded), Toast.LENGTH_SHORT).show()
                    if (thenOpen) openInLumen(context, u, info)
                }
                .onFailure { e -> Toast.makeText(context, context.getString(R.string.download_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show() }
            progress.remove(info.handle)
        }
    }

    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(info.fileName, style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(
                    sizeText(context, info.size),
                    if (info.width > 0) "${info.width} × ${info.height}" else null,
                    info.captureDate.takeIf { it.isNotBlank() }?.let(::prettyDate),
                ).joinToString(" · "),
                style = CtrlType.Data,
                color = CtrlColors.Muted,
            )
            if (p != null) {
                CtrlProgressBar(p, color = CtrlColors.Accent)
                Text(stringResource(R.string.downloading, (p * 100).toInt()), style = CtrlType.Data, color = CtrlColors.Muted)
            } else if (uri == null) {
                CtrlButton(stringResource(R.string.download), onClick = { download(false) }, icon = Icons.Outlined.Download, modifier = Modifier.fillMaxWidth())
                if (lumen && info.isImage) {
                    GroupRow(stringResource(R.string.download_open_lumen), stringResource(R.string.download_open_lumen_sub), icon = Icons.Outlined.OpenInNew, onClick = { download(true) })
                }
            } else {
                Text(stringResource(R.string.saved_to), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted)
                if (lumen && info.isImage) {
                    CtrlButton(stringResource(R.string.open_lumen), onClick = { openInLumen(context, uri, info) }, icon = Icons.Outlined.OpenInNew, modifier = Modifier.fillMaxWidth())
                }
            }
            if (!lumen) Text(stringResource(R.string.lumen_missing), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
        }
    }
}

private fun openInLumen(context: android.content.Context, uri: Uri, info: ObjectInfo) {
    val intent = Saver.lumenIntent(context, uri, info.mime)
    if (intent == null || !context.launch(intent)) {
        Toast.makeText(context, context.getString(R.string.lumen_failed), Toast.LENGTH_SHORT).show()
    }
}

/** PTP dates are "YYYYMMDDThhmmss"; shown as "YYYY-MM-DD hh:mm". */
private fun prettyDate(s: String): String {
    val m = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})""").find(s) ?: return s
    val (y, mo, d, h, mi) = m.destructured
    return "$y-$mo-$d $h:$mi"
}
