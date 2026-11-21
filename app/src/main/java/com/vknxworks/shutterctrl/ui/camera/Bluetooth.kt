package com.vknxworks.shutterctrl.ui.camera

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.ble.BleRemote
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * The camera as a Bluetooth remote: pair once, then Focus and Shoot over Bluetooth, without the camera's Wi-Fi.
 * Marked "Needs confirming on your device": it follows two open-source projects but hasn't been tried on an R50.
 */
@Composable
fun BluetoothRemoteGroup() {
    val context = LocalContext.current
    val c = context.container
    val ble = c.ble
    val scope = rememberCoroutineScope()
    val state by ble.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusing by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it }) next?.invoke()
        else Toast.makeText(context, context.getString(R.string.ble_permission_denied), Toast.LENGTH_LONG).show()
    }
    fun withPermission(action: () -> Unit) {
        if (ble.hasPermissions()) action() else { afterPermission = action; ask.launch(BleRemote.PERMISSIONS) }
    }
    fun rememberCamera(address: String, name: String) {
        scope.launch { c.settings.update { it.copy(bleAddress = address, bleName = name) } }
    }

    Group(stringResource(R.string.ble_title), footer = stringResource(R.string.ble_footer)) {
        GroupRow(stringResource(R.string.needs_confirming), stringResource(R.string.ble_confirm_sub), icon = Icons.Outlined.Bluetooth, iconTint = CtrlColors.Muted)
        when (val s = state) {
            is BleRemote.State.Connected -> {
                GroupRow(stringResource(R.string.ble_connected, s.name), stringResource(R.string.ble_connected_sub), icon = Icons.Outlined.BluetoothConnected, iconTint = CtrlColors.AccentText)
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CtrlSecondaryButton(
                        onClick = {
                            scope.launch {
                                val on = !focusing
                                if (ble.focus(on)) focusing = on else { focusing = false; Toast.makeText(context, context.getString(R.string.ble_send_failed), Toast.LENGTH_SHORT).show() }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.CenterFocusStrong, null, tint = if (focusing) CtrlColors.AccentText else CtrlColors.Text)
                        Text(stringResource(if (focusing) R.string.focus_release else R.string.focus), Modifier.padding(start = 8.dp), color = if (focusing) CtrlColors.AccentText else CtrlColors.Text)
                    }
                    CtrlButton(
                        stringResource(R.string.shoot),
                        onClick = {
                            scope.launch {
                                focusing = false
                                if (!ble.shoot()) Toast.makeText(context, context.getString(R.string.ble_send_failed), Toast.LENGTH_SHORT).show()
                            }
                        },
                        icon = Icons.Outlined.PhotoCamera,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                GroupRow(stringResource(R.string.ble_disconnect), icon = Icons.Outlined.LinkOff, onClick = { focusing = false; ble.disconnect() })
            }
            is BleRemote.State.Connecting ->
                GroupRow(stringResource(R.string.ble_connecting, s.name), stringResource(R.string.ble_connecting_sub), icon = Icons.Outlined.BluetoothSearching)
            is BleRemote.State.Scanning -> {
                GroupRow(stringResource(R.string.ble_searching), stringResource(R.string.ble_searching_sub), icon = Icons.Outlined.BluetoothSearching, onClick = { ble.stopScan(); ble.disconnect() }, value = stringResource(R.string.ble_stop))
                s.found.forEach { f ->
                    GroupRow(f.name, f.address, icon = Icons.Outlined.PhotoCamera, showChevron = true, onClick = { withPermission { ble.connect(f.address, f.name) { a, n -> rememberCamera(a, n) } } })
                }
            }
            is BleRemote.State.Failed, BleRemote.State.Idle -> {
                if (s is BleRemote.State.Failed) {
                    GroupRow(stringResource(failText(s.reason)), icon = Icons.Outlined.Bluetooth, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { ble.dismiss() })
                }
                if (settings.bleAddress.isNotEmpty()) {
                    GroupRow(
                        stringResource(R.string.ble_connect, settings.bleName.ifBlank { settings.bleAddress }),
                        stringResource(R.string.ble_connect_sub),
                        icon = Icons.Outlined.BluetoothConnected,
                        showChevron = true,
                        onClick = { withPermission { ble.connect(settings.bleAddress, settings.bleName.ifBlank { settings.bleAddress }) } },
                    )
                    GroupRow(
                        stringResource(R.string.ble_forget),
                        stringResource(R.string.ble_forget_sub),
                        icon = Icons.Outlined.Delete,
                        onClick = { scope.launch { c.settings.update { it.copy(bleAddress = "", bleName = "") } } },
                    )
                }
                GroupRow(
                    stringResource(if (settings.bleAddress.isEmpty()) R.string.ble_pair else R.string.ble_pair_other),
                    stringResource(R.string.ble_pair_sub),
                    icon = Icons.Outlined.BluetoothSearching,
                    showChevron = true,
                    onClick = { withPermission { ble.scan() } },
                )
            }
        }
    }
}

private fun failText(reason: BleRemote.Reason): Int = when (reason) {
    BleRemote.Reason.NO_BLUETOOTH -> R.string.ble_err_no_bluetooth
    BleRemote.Reason.BLUETOOTH_OFF -> R.string.ble_err_off
    BleRemote.Reason.NO_PERMISSION -> R.string.ble_permission_denied
    BleRemote.Reason.NOT_FOUND -> R.string.ble_err_not_found
    BleRemote.Reason.CONNECT -> R.string.ble_err_connect
    BleRemote.Reason.NOT_CANON_REMOTE -> R.string.ble_err_not_remote
    BleRemote.Reason.WRITE -> R.string.ble_err_write
    BleRemote.Reason.LOST -> R.string.ble_err_lost
}
