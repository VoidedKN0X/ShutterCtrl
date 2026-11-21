package com.vknxworks.shutterctrl.ui.card

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.vknxworks.shutterctrl.camera.AutoDownload
import com.vknxworks.shutterctrl.camera.Link
import com.vknxworks.shutterctrl.camera.Saver
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ptp.ObjectInfo
import com.vknxworks.shutterctrl.ui.common.launch
import com.vknxworks.shutterctrl.ui.common.sizeText
import com.vknxworks.shutterctrl.ui.components.ChoiceSheet
import com.vknxworks.shutterctrl.ui.components.ConfirmDialog
import com.vknxworks.shutterctrl.ui.components.CtrlBottomSheet
import com.vknxworks.shutterctrl.ui.components.CtrlSecondaryButton
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

/** Which files the Card tab shows. TODAY means taken today (by the camera's clock). */
private enum class CardFilter { ALL, JPEG, RAW, TODAY }

private fun ObjectInfo.matches(f: CardFilter, today: String): Boolean = when (f) {
    CardFilter.ALL -> true
    CardFilter.JPEG -> isJpeg
    CardFilter.RAW -> isRaw
    CardFilter.TODAY -> captureDate.startsWith(today)
}

/**
 * The Card tab: the camera's card as thumbnails. Download one file (tap it), or long-press to select several and
 * download or delete them together; filter by JPEG, RAW or today; and let new photos download by themselves.
 */
@Composable
fun CardScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val link by c.session.link.collectAsStateWithLifecycle()
    val card by c.session.card.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val downloads = c.downloads
    var open by remember { mutableStateOf<ObjectInfo?>(null) }
    var filter by remember { mutableStateOf(CardFilter.ALL) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<ObjectInfo>?>(null) }
    var pickAuto by remember { mutableStateOf(false) }
    val connected = link is Link.Connected
    LaunchedEffect(connected) { if (connected && !card.loaded && !card.loading) c.session.loadCard() }
    LaunchedEffect(connected) { if (!connected) selected = emptySet() }
    val today = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) }
    val shown = remember(card.items, filter, today) { card.items.filter { it.matches(filter, today) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_card)) {
            if (connected) CtrlSquareButton(Icons.Outlined.Refresh, stringResource(R.string.refresh), { selected = emptySet(); c.session.loadCard() }, enabled = !card.loading)
        }
        if (connected) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(filterLabel(f))) },
                    )
                }
                FilterChip(
                    selected = settings.autoDownload != AutoDownload.OFF,
                    onClick = { pickAuto = true },
                    label = { Text(stringResource(R.string.auto_download_chip, stringResource(autoLabel(settings.autoDownload)))) },
                    leadingIcon = { Icon(Icons.Outlined.DownloadForOffline, null) },
                )
            }
        }
        if (downloads.runTotal > 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) {
                val finished = downloads.runDone + downloads.runFailed
                CtrlProgressBar(finished.toFloat() / downloads.runTotal, color = CtrlColors.Accent)
                Text(
                    if (downloads.runFailed > 0) stringResource(R.string.batch_progress_failed, finished, downloads.runTotal, downloads.runFailed)
                    else stringResource(R.string.batch_progress, finished, downloads.runTotal),
                    style = CtrlType.Data,
                    color = CtrlColors.Muted,
                )
            }
        }
        if (selected.isNotEmpty()) {
            val picked = card.items.filter { it.handle in selected }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.selected_count, picked.size), style = MaterialTheme.typography.titleSmall)
                CtrlSquareButton(Icons.Outlined.SelectAll, stringResource(R.string.select_all), { selected = selected + shown.map { it.handle } })
                CtrlSquareButton(Icons.Outlined.Download, stringResource(R.string.download_selected), {
                    val added = downloads.enqueueAll(picked)
                    if (added == 0) Toast.makeText(context, context.getString(R.string.batch_nothing_new), Toast.LENGTH_SHORT).show()
                    selected = emptySet()
                })
                if (c.session.canDelete) {
                    CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.delete_selected), { confirmDelete = picked })
                }
                CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.clear_selection), { selected = emptySet() })
            }
        }
        when {
            !connected -> MessageView(Icons.Outlined.LinkOff, stringResource(R.string.card_not_connected), stringResource(R.string.card_not_connected_body))
            card.error != null -> MessageView(Icons.Outlined.SdCard, stringResource(R.string.card_error), card.error, actionLabel = stringResource(R.string.retry), onAction = { c.session.loadCard() })
            card.items.isEmpty() && card.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CtrlColors.AccentText) }
            card.items.isEmpty() -> MessageView(Icons.Outlined.SdCard, stringResource(R.string.card_empty), stringResource(R.string.card_empty_body))
            shown.isEmpty() -> MessageView(Icons.Outlined.SdCard, stringResource(R.string.card_filter_empty), stringResource(R.string.card_filter_empty_body))
            else -> {
                if (card.loading) CtrlProgressBar(0.5f, Modifier.padding(horizontal = Gutter), color = CtrlColors.Accent)
                LazyVerticalGrid(
                    GridCells.Adaptive(108.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.handle }) { info ->
                        val isSelected = info.handle in selected
                        Tile(
                            info,
                            selected = isSelected,
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (isSelected) selected - info.handle else selected + info.handle
                                else open = info
                            },
                            onLongClick = { selected = if (isSelected) selected - info.handle else selected + info.handle },
                        )
                    }
                }
            }
        }
    }
    open?.let { info -> FileSheet(info, onDelete = { open = null; confirmDelete = listOf(info) }) { open = null } }
    if (pickAuto) {
        ChoiceSheet(
            stringResource(R.string.auto_download),
            AutoDownload.entries.map { it to stringResource(autoLabel(it)) },
            settings.autoDownload,
            onDismiss = { pickAuto = false },
        ) { v ->
            pickAuto = false
            scope.launch { c.settings.update { it.copy(autoDownload = v) } }
        }
    }
    confirmDelete?.let { files ->
        ConfirmDialog(
            stringResource(R.string.delete_title),
            if (files.size == 1) stringResource(R.string.delete_body_one, files.first().fileName)
            else stringResource(R.string.delete_body_many, files.size),
            stringResource(R.string.delete_confirm),
            onDismiss = { confirmDelete = null },
        ) {
            confirmDelete = null
            selected = emptySet()
            c.appScope.launch {
                var failed = 0
                for (f in files) {
                    runCatching { c.session.delete(f) }.onSuccess { downloads.forget(f.handle) }.onFailure { failed++ }
                }
                val msg = if (failed == 0) context.getString(R.string.deleted, files.size) else context.getString(R.string.delete_failed, failed)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun filterLabel(f: CardFilter) = when (f) {
    CardFilter.ALL -> R.string.filter_all
    CardFilter.JPEG -> R.string.filter_jpeg
    CardFilter.RAW -> R.string.filter_raw
    CardFilter.TODAY -> R.string.filter_today
}

private fun autoLabel(a: AutoDownload) = when (a) {
    AutoDownload.OFF -> R.string.auto_off
    AutoDownload.JPEG -> R.string.auto_jpeg
    AutoDownload.RAW -> R.string.auto_raw
    AutoDownload.ALL -> R.string.auto_all
}

@Composable
private fun Tile(info: ObjectInfo, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
    Box(
        Modifier.aspectRatio(1f).clip(shape).background(CtrlColors.Surface)
            .then(if (selected) Modifier.border(3.dp, CtrlColors.Accent, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
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
        if (selected) {
            Icon(Icons.Outlined.CheckCircle, stringResource(R.string.selected), Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp), tint = CtrlColors.AccentText)
        }
        if (c.downloads.done.containsKey(info.handle)) {
            Icon(Icons.Outlined.Download, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp), tint = CtrlColors.AccentText)
        } else if (c.downloads.progress.containsKey(info.handle)) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp), color = CtrlColors.AccentText, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun FileSheet(info: ObjectInfo, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val lumen = remember { Saver.lumenInstalled(context) }
    val p = c.downloads.progress[info.handle]
    val uri = c.downloads.done[info.handle]
    val resumeAt = remember(info.handle, p) { c.session.resumeFraction(info) }

    fun download(thenOpen: Boolean) {
        c.downloads.enqueue(info) { result ->
            result.onSuccess { u ->
                val inDownloads = u.toString().contains("/downloads/")
                Toast.makeText(context, context.getString(if (inDownloads) R.string.downloaded_dl else R.string.downloaded), Toast.LENGTH_SHORT).show()
                if (thenOpen) openInLumen(context, u, info)
            }.onFailure { e ->
                val left = c.session.resumeFraction(info)
                val msg = if (left != null) context.getString(R.string.download_interrupted, (left * 100).toInt())
                else context.getString(R.string.download_failed, e.message.orEmpty())
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
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
                CtrlButton(
                    if (resumeAt != null) stringResource(R.string.download_resume, (resumeAt * 100).toInt()) else stringResource(R.string.download),
                    onClick = { download(false) },
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            if (c.session.canDelete && p == null) {
                CtrlSecondaryButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Delete, null, tint = CtrlColors.Error)
                    Text(stringResource(R.string.delete_from_card), Modifier.padding(start = 8.dp), color = CtrlColors.Error)
                }
            }
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
