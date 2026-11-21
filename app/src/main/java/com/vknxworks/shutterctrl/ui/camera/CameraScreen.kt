package com.vknxworks.shutterctrl.ui.camera

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.camera.Link
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.net.LocalAddress
import com.vknxworks.shutterctrl.net.Ssdp
import com.vknxworks.shutterctrl.ptp.ExposureValues
import com.vknxworks.shutterctrl.ptp.Prop
import com.vknxworks.shutterctrl.ui.common.hasPermission
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlHeroCard
import com.vknxworks.shutterctrl.ui.components.CtrlProgressBar
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.components.NumberInput
import com.vknxworks.shutterctrl.ui.components.TabTitle
import com.vknxworks.shutterctrl.ui.components.TextInput
import com.vknxworks.shutterctrl.ui.components.WarningCard
import com.vknxworks.shutterctrl.ui.components.bottomClearance
import com.vknxworks.shutterctrl.ui.nav.FirmwareRoute
import com.vknxworks.shutterctrl.ui.nav.LiveViewRoute
import com.vknxworks.shutterctrl.ui.nav.LocalNavigator
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.CtrlType
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Camera tab: connect, then shoot, change exposure, live view, the intervalometer and firmware. */
@Composable
fun CameraScreen() {
    val c = LocalContext.current.container
    val link by c.session.link.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_camera))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).bottomClearance()) {
            when (val l = link) {
                is Link.Connected -> ConnectedPanel(l)
                else -> ConnectPanel(l)
            }
            // Independent of the Wi-Fi or USB connection: the camera's own Bluetooth remote.
            BluetoothRemoteGroup()
        }
    }
}

@Composable
private fun ConnectPanel(link: Link) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val wifi by c.network.wifi.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Ssdp.Found>?>(null) }
    LaunchedEffect(settings.lastHost) { if (host.isEmpty()) host = settings.lastHost }

    fun connect(text: String) {
        scope.launch {
            val guid = c.settings.guid()
            c.settings.update { it.copy(lastHost = text.trim()) }
            c.session.connect(text.trim(), guid, context.getString(R.string.app_name))
        }
    }

    val wifiText = when {
        wifi.network == null -> stringResource(R.string.wifi_none)
        !wifi.hasInternet -> stringResource(R.string.wifi_camera)
        else -> stringResource(R.string.wifi_home)
    }
    CtrlHeroCard(
        stringResource(if (link is Link.Connecting) R.string.status_connecting else R.string.status_not_connected),
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
        meta = wifiText,
    ) {
        if (link is Link.Connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = CtrlColors.OnAccent, strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(link.host, style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(if (link.usb) R.string.connecting_hint_usb else R.string.connecting_hint), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.connect_body), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (link is Link.Failed) {
        val text = when (link.reason) {
            Link.Reason.UNREACHABLE -> stringResource(R.string.fail_unreachable, link.host)
            Link.Reason.REFUSED -> stringResource(R.string.fail_refused)
            Link.Reason.LOST -> stringResource(R.string.fail_lost)
            Link.Reason.NOT_CANON -> stringResource(R.string.fail_not_canon)
            Link.Reason.NOT_LOCAL -> stringResource(R.string.fail_not_local)
            Link.Reason.USB_DETACHED -> stringResource(R.string.fail_usb_detached)
            Link.Reason.USB_DENIED -> stringResource(R.string.fail_usb_denied)
            Link.Reason.USB_OPEN -> stringResource(R.string.fail_usb_open)
        }
        WarningCard(text, Icons.Outlined.ErrorOutline, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
    }

    if (link !is Link.Connecting) {
        val usbCameras by c.usb.cameras.collectAsStateWithLifecycle()
        Group(stringResource(R.string.group_usb), footer = stringResource(R.string.usb_footer)) {
            if (usbCameras.isEmpty()) {
                GroupRow(stringResource(R.string.usb_none), stringResource(R.string.usb_none_sub), icon = Icons.Outlined.Usb)
            }
            usbCameras.forEach { cam ->
                GroupRow(
                    cam.name.ifBlank { stringResource(R.string.usb_camera_default) },
                    stringResource(R.string.usb_camera_sub),
                    icon = Icons.Outlined.Usb,
                    showChevron = true,
                    onClick = { c.session.connectUsb(cam, context.getString(R.string.usb_label)) },
                )
            }
        }
        Group(stringResource(R.string.group_find), footer = stringResource(R.string.find_footer)) {
            GroupRow(
                stringResource(if (searching) R.string.searching else R.string.search_network),
                stringResource(R.string.search_network_sub),
                icon = Icons.Outlined.Search,
                onClick = if (searching) null else {
                    {
                        searching = true
                        found = null
                        scope.launch {
                            val wm = context.getSystemService(android.net.wifi.WifiManager::class.java)
                            val lock = runCatching { wm.createMulticastLock("shutterctrl-ssdp").apply { setReferenceCounted(false); acquire() } }.getOrNull()
                            found = try {
                                withContext(Dispatchers.IO) { runCatching { Ssdp.search(c.network::bind, c.network.socketFactory()) }.getOrDefault(emptyList()) }
                            } finally {
                                runCatching { lock?.release() }
                            }
                            searching = false
                        }
                    }
                },
                trailing = if (searching) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
            )
            found?.let { list ->
                if (list.isEmpty()) {
                    GroupRow(stringResource(R.string.search_none), stringResource(R.string.search_none_sub))
                }
                list.forEach { f ->
                    GroupRow(
                        f.name.ifBlank { f.model },
                        f.address.hostAddress,
                        icon = Icons.Outlined.CameraAlt,
                        showChevron = true,
                        onClick = { host = f.address.hostAddress.orEmpty(); connect(host) },
                    )
                }
            }
            wifi.gateway?.let { gw ->
                if (!wifi.hasInternet) {
                    GroupRow(
                        stringResource(R.string.use_gateway),
                        stringResource(R.string.use_gateway_sub, gw.hostAddress.orEmpty()),
                        icon = Icons.Outlined.Router,
                        showChevron = true,
                        onClick = { host = gw.hostAddress.orEmpty(); connect(host) },
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = Gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(
                stringResource(R.string.camera_address),
                host,
                { host = it.take(45) },
                placeholder = "192.168.1.2",
                keyboard = androidx.compose.ui.text.input.KeyboardType.Uri,
            )
            val valid = LocalAddress.parse(host) != null
            CtrlButton(
                stringResource(R.string.connect),
                onClick = { connect(host) },
                enabled = valid,
                icon = Icons.Outlined.Cable,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.address_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Group(stringResource(R.string.group_howto)) {
            GroupRow(stringResource(R.string.howto_1_title), stringResource(R.string.howto_1))
            GroupRow(stringResource(R.string.howto_2_title), stringResource(R.string.howto_2))
            GroupRow(stringResource(R.string.howto_3_title), stringResource(R.string.howto_3))
        }
    }
}

@Composable
private fun ConnectedPanel(link: Link.Connected) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val props by c.session.props.collectAsStateWithLifecycle()
    val battery = props[Prop.BATTERY]?.let { ExposureValues.batteryPercent(it) }

    CtrlHeroCard(
        stringResource(R.string.status_connected),
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
        meta = battery?.let { stringResource(R.string.battery_pct, it) },
    ) {
        Text(link.info.model.ifBlank { link.name }, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.connected_sub, link.host, link.info.deviceVersion), style = CtrlType.Data)
    }

    ExposureRow()
    MoreSettingsRow()
    ShutterButtons()
    BulbGroup()
    Group {
        SelfTimerRow()
        GroupRow(
            stringResource(R.string.live_view),
            stringResource(if (c.session.canLiveView) R.string.live_view_sub else R.string.live_view_unsupported),
            icon = Icons.Outlined.Videocam,
            showChevron = c.session.canLiveView,
            onClick = if (c.session.canLiveView) { { nav.navigate(LiveViewRoute) } } else null,
        )
    }
    IntervalGroup()
    BracketGroup()
    Group(stringResource(R.string.group_camera)) {
        GroupRow(
            stringResource(R.string.firmware),
            stringResource(R.string.firmware_sub),
            icon = Icons.Outlined.SystemUpdateAlt,
            showChevron = true,
            onClick = { nav.navigate(FirmwareRoute) },
        )
        GroupRow(
            stringResource(R.string.disconnect),
            stringResource(R.string.disconnect_sub),
            icon = Icons.Outlined.LinkOff,
            onClick = {
                IntervalService.stop(context)
                c.session.disconnect()
            },
        )
    }
}

@Composable
private fun IntervalGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val state by IntervalService.state.collectAsStateWithLifecycle()
    var shots by rememberSaveable { mutableStateOf("") }
    var every by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settings.intervalShots, settings.intervalSec) {
        if (shots.isEmpty()) shots = settings.intervalShots.toString()
        if (every.isEmpty()) every = settings.intervalSec.toString()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Group(stringResource(R.string.group_interval), footer = stringResource(R.string.interval_footer)) {
        if (state.active) {
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
            val left = ((state.nextAtElapsed - now) / 1000).coerceAtLeast(0)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.interval_progress, state.shots, state.total), style = MaterialTheme.typography.titleMedium)
                CtrlProgressBar(state.shots.toFloat() / state.total.coerceAtLeast(1), color = CtrlColors.Accent)
                Text(stringResource(R.string.interval_next, left), style = CtrlType.Data, color = CtrlColors.Muted)
                if (state.failed > 0) Text(stringResource(R.string.interval_failed, state.failed), style = CtrlType.Data, color = CtrlColors.Error)
                CtrlButton(stringResource(R.string.stop), onClick = { IntervalService.stop(context) }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberInput(stringResource(R.string.interval_shots), shots, { shots = it }, Modifier.weight(1f), decimals = false)
                    NumberInput(stringResource(R.string.interval_every), every, { every = it }, Modifier.weight(1f), suffix = "s", decimals = false)
                }
                val n = shots.toIntOrNull()
                val t = every.toIntOrNull()
                val valid = n != null && n in 1..IntervalService.MAX_SHOTS && t != null && t in IntervalService.MIN_INTERVAL..IntervalService.MAX_INTERVAL
                if (!valid) Text(stringResource(R.string.interval_limits), style = CtrlType.Data, color = CtrlColors.Muted)
                CtrlButton(
                    stringResource(R.string.interval_start),
                    onClick = {
                        if (n == null || t == null) return@CtrlButton
                        scope.launch { c.settings.update { it.copy(intervalShots = n, intervalSec = t) } }
                        if (Build.VERSION.SDK_INT >= 33 && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        IntervalService.start(context, n, t)
                    },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
