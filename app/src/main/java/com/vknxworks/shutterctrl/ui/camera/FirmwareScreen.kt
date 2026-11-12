package com.vknxworks.shutterctrl.ui.camera

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.FirmwareRules
import com.vknxworks.shutterctrl.camera.FirmwareRules.Block
import com.vknxworks.shutterctrl.camera.FirmwareRules.Route
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.camera.Link
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ptp.ExposureValues
import com.vknxworks.shutterctrl.ptp.Prop
import com.vknxworks.shutterctrl.ui.common.sizeText
import com.vknxworks.shutterctrl.ui.components.ConfirmDialog
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlCard
import com.vknxworks.shutterctrl.ui.components.CtrlProgressBar
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.components.MessageView
import com.vknxworks.shutterctrl.ui.components.ScreenTopBar
import com.vknxworks.shutterctrl.ui.components.WarningCard
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.CtrlType
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The firmware transfer, kept outside the screen so it survives the screen being rebuilt. */
object FirmwareTask {
    sealed interface State {
        data object Idle : State
        data class Running(val progress: Float, val route: Route) : State
        data class Done(val route: Route, val fileName: String) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
}

private data class Picked(val uri: Uri, val name: String, val size: Long, val modelId: Long?)

@Composable
fun FirmwareScreen() {
    val context = LocalContext.current
    val c = context.container
    val session = c.session
    val scope = rememberCoroutineScope()
    val link by session.link.collectAsStateWithLifecycle()
    val props by session.props.collectAsStateWithLifecycle()
    val task by FirmwareTask.state.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<Picked?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var modelAck by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            picked = withContext(Dispatchers.IO) {
                runCatching {
                    var name = ""
                    var size = -1L
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                        if (cur.moveToFirst()) {
                            name = cur.getString(0).orEmpty()
                            if (!cur.isNull(1)) size = cur.getLong(1)
                        }
                    }
                    val header = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(16) } ?: ByteArray(0)
                    Picked(uri, name, size, FirmwareRules.firModelId(header))
                }.getOrNull()
            }
            modelAck = false
            if (FirmwareTask.state.value !is FirmwareTask.State.Running) FirmwareTask.state.value = FirmwareTask.State.Idle
        }
    }

    // While the transfer runs: no way back, screen stays on.
    val running = task is FirmwareTask.State.Running
    BackHandler(enabled = running) { }
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.firmware), showBack = !running)
        when {
            task is FirmwareTask.State.Running -> {
                val t = task as FirmwareTask.State.Running
                Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WarningCard(stringResource(R.string.fw_running_warning), Icons.Outlined.Warning)
                    Text(stringResource(if (t.route == Route.CANON_TRANSFER) R.string.fw_sending else R.string.fw_copying), style = MaterialTheme.typography.titleLarge)
                    CtrlProgressBar(t.progress, color = CtrlColors.Accent)
                    Text("${(t.progress * 100).toInt()} %", style = CtrlType.Readout)
                }
                return@Column
            }
            link !is Link.Connected -> {
                MessageView(Icons.Outlined.LinkOff, stringResource(R.string.fw_not_connected), stringResource(R.string.fw_not_connected_body))
                return@Column
            }
        }
        val route = session.firmwareRoute
        val cameraModelId = props[Prop.MODEL_ID]
        val battery = props[Prop.BATTERY]
        val p = picked
        val check = p?.let { FirmwareRules.check(it.name, it.size, it.modelId, cameraModelId, battery, route) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            when (val t = task) {
                is FirmwareTask.State.Done -> CtrlCard(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Outlined.CheckCircle, null, tint = CtrlColors.AccentText)
                        Text(stringResource(R.string.fw_done_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                    }
                    Text(
                        stringResource(if (t.route == Route.CANON_TRANSFER) R.string.fw_done_canon else R.string.fw_done_card, t.fileName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is FirmwareTask.State.Failed -> WarningCard(stringResource(R.string.fw_failed, t.message), Icons.Outlined.ErrorOutline, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
                else -> {}
            }
            Text(
                stringResource(R.string.fw_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = CtrlColors.Muted,
                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 6.dp),
            )
            Group(stringResource(R.string.fw_group_camera)) {
                GroupRow(stringResource(R.string.fw_camera), value = (link as Link.Connected).info.model)
                GroupRow(stringResource(R.string.fw_current_version), value = (link as Link.Connected).info.deviceVersion.ifBlank { "--" })
                GroupRow(
                    stringResource(R.string.fw_battery),
                    value = battery?.let { ExposureValues.batteryPercent(it)?.let { pct -> "$pct %" } } ?: stringResource(R.string.unknown),
                )
                GroupRow(
                    stringResource(R.string.fw_route),
                    stringResource(
                        when (route) {
                            Route.CANON_TRANSFER -> R.string.fw_route_canon
                            Route.COPY_TO_CARD -> R.string.fw_route_card
                            Route.NONE -> R.string.fw_route_none
                        },
                    ),
                )
            }
            Group(stringResource(R.string.fw_group_file)) {
                GroupRow(
                    p?.name ?: stringResource(R.string.fw_pick),
                    p?.let { stringResource(R.string.fw_file_sub, sizeText(context, it.size.coerceAtLeast(0)), it.modelId?.let { id -> "0x%08X".format(id) } ?: "--") }
                        ?: stringResource(R.string.fw_pick_sub),
                    icon = Icons.Outlined.FileOpen,
                    showChevron = true,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
            }
            if (check != null) {
                Column(Modifier.padding(horizontal = Gutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    check.blocks.forEach { b -> WarningCard(blockText(b), Icons.Outlined.ErrorOutline) }
                    if (check.blocks.isEmpty() && !check.modelConfirmed) {
                        CtrlCard(Modifier.fillMaxWidth(), onClick = { modelAck = !modelAck }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(modelAck, { modelAck = it }, colors = CheckboxDefaults.colors(checkedColor = CtrlColors.Accent, checkmarkColor = CtrlColors.OnAccent))
                                Text(stringResource(R.string.fw_model_ack, (link as Link.Connected).info.model), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (check.modelConfirmed) {
                        Text(stringResource(R.string.fw_model_ok), style = CtrlType.Data, color = CtrlColors.AccentText, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    CtrlButton(
                        stringResource(if (route == Route.CANON_TRANSFER) R.string.fw_send else R.string.fw_copy),
                        onClick = { confirm = true },
                        enabled = check.blocks.isEmpty() && (check.modelConfirmed || modelAck),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (confirm && p != null) {
            ConfirmDialog(
                stringResource(R.string.fw_confirm_title),
                stringResource(if (route == Route.CANON_TRANSFER) R.string.fw_confirm_canon else R.string.fw_confirm_card, p.name),
                stringResource(if (route == Route.CANON_TRANSFER) R.string.fw_send else R.string.fw_copy),
                onDismiss = { confirm = false },
            ) {
                confirm = false
                IntervalService.stop(context)
                FirmwareTask.state.value = FirmwareTask.State.Running(0f, route)
                // In the app scope: leaving this screen (or the lock screen coming up) doesn't stop it.
                c.appScope.launch {
                    val open = { context.contentResolver.openInputStream(p.uri) ?: throw java.io.IOException("Can't read the file") }
                    val progress = { f: Float -> FirmwareTask.state.value = FirmwareTask.State.Running(f, route) }
                    runCatching {
                        if (route == Route.CANON_TRANSFER) session.sendFirmwareCanon(p.name, p.size, open, progress)
                        else session.copyFirmwareToCard(p.name, p.size, open, progress)
                    }.onSuccess {
                        FirmwareTask.state.value = FirmwareTask.State.Done(route, p.name)
                    }.onFailure { e ->
                        FirmwareTask.state.value = FirmwareTask.State.Failed(e.message.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
private fun blockText(b: Block): String = stringResource(
    when (b) {
        Block.NOT_FIR -> R.string.fw_block_not_fir
        Block.BAD_SIZE -> R.string.fw_block_size
        Block.NAME_TOO_LONG -> R.string.fw_block_name
        Block.BATTERY_UNKNOWN -> R.string.fw_block_battery_unknown
        Block.BATTERY_LOW -> R.string.fw_block_battery_low
        Block.MODEL_MISMATCH -> R.string.fw_block_model
        Block.NO_ROUTE -> R.string.fw_block_route
    },
)
