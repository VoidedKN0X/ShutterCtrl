package com.vknxworks.shutterctrl.ui.settings

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.data.lock.LockVault
import com.vknxworks.shutterctrl.data.settings.AppSettings
import com.vknxworks.shutterctrl.data.settings.LockTimeout
import com.vknxworks.shutterctrl.data.settings.SettingsRepository
import com.vknxworks.shutterctrl.ui.camera.SelfTimerRow
import com.vknxworks.shutterctrl.ui.components.ConfirmDialog
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupChoice
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.components.GroupSwitch
import com.vknxworks.shutterctrl.ui.components.ScreenTopBar
import com.vknxworks.shutterctrl.ui.lock.AppLock
import com.vknxworks.shutterctrl.ui.lock.PinRequest
import com.vknxworks.shutterctrl.ui.lock.findActivity
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). ShutterCtrl follows the phone's dark theme. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var resetAsk by remember { mutableStateOf(false) }
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch { c.settings.update(transform) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(stringResource(R.string.group_shooting)) {
                SelfTimerRow()
                GroupSwitch(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_sub), settings.keepScreenOn, Icons.Outlined.LightMode) { on -> set { it.copy(keepScreenOn = on) } }
            }
            GeotagGroup(settings)
            Group(stringResource(R.string.group_connection), footer = stringResource(R.string.connection_footer)) {
                GroupRow(
                    stringResource(R.string.reset_identity),
                    stringResource(R.string.reset_identity_sub),
                    icon = Icons.Outlined.Key,
                    onClick = { resetAsk = true },
                )
            }
            PrivacyAndSecurityGroup(settings)
        }
    }
    if (resetAsk) {
        ConfirmDialog(
            stringResource(R.string.reset_identity_title),
            stringResource(R.string.reset_identity_body),
            stringResource(R.string.reset_identity_confirm),
            onDismiss = { resetAsk = false },
        ) {
            resetAsk = false
            c.session.disconnect()
            set { it.copy(guid = SettingsRepository.newGuid()) }
            Toast.makeText(context, context.getString(R.string.reset_identity_done), Toast.LENGTH_SHORT).show()
        }
    }
}
/**
 * Geotag downloads (off until turned on). The location permission is asked for only when it's turned on;
 * if it's refused, the option stays off.
 */
@Composable
private fun GeotagGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(c.geo.permitted) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permitted = c.geo.permitted
        c.geo.permissionChanged()
        if (result.values.any { it }) scope.launch { c.settings.update { it.copy(geotag = true) } }
        else Toast.makeText(context, context.getString(R.string.geotag_denied), Toast.LENGTH_LONG).show()
    }
    val sub = when {
        settings.geotag && !permitted -> stringResource(R.string.geotag_sub_no_permission)
        settings.geotag && c.geo.approximateOnly -> stringResource(R.string.geotag_sub_approximate)
        else -> stringResource(R.string.geotag_sub)
    }
    Group(stringResource(R.string.group_downloads), footer = stringResource(R.string.geotag_footer)) {
        GroupSwitch(stringResource(R.string.geotag), sub, settings.geotag, Icons.Outlined.LocationOn) { on ->
            if (!on) {
                scope.launch { c.settings.update { it.copy(geotag = false) } }
            } else if (c.geo.permitted) {
                scope.launch { c.settings.update { it.copy(geotag = true) } }
            } else {
                ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
}

/** Privacy and security: the shared Ctrl series app lock, then hiding ShutterCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}
