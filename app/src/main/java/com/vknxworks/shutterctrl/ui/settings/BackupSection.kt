package com.vknxworks.shutterctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.shutterctrl.BuildConfig
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.data.settings.BackupFormatException
import com.vknxworks.shutterctrl.data.settings.SettingsBackup
import com.vknxworks.shutterctrl.data.settings.SettingsBackups
import com.vknxworks.shutterctrl.ui.components.ConfirmDialog
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupRow
import com.vknxworks.shutterctrl.ui.lock.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Export and import of ShutterCtrl's settings: a JSON file in a place you pick (Downloads, a cloud folder…).
 * No pairing identity, Bluetooth camera, geotagging, app lock or privacy settings in it; an import shows which
 * settings it changes and asks first.
 */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<SettingsBackup?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = SettingsBackups.export(c.settings.settings.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException()
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // A settings file is a couple of kilobytes; anything far bigger isn't one.
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw BackupFormatException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException()
                }
                SettingsBackups.parse(text)
            }
            result.onSuccess { pending = it }.onFailure { e ->
                val msg = if (e is BackupFormatException && e.newer) R.string.backup_too_new else R.string.backup_not_valid
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.backup_title), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            stringResource(R.string.backup_save),
            stringResource(R.string.backup_save_sub),
            Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("shutterctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.backup_restore),
            stringResource(R.string.backup_restore_sub),
            Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { b ->
        val current = c.settings.settings.value
        val changes = SettingsBackups.changed(current, SettingsBackups.applied(current, b.settings))
        val names = changes.map { stringResource(it) }
        if (names.isEmpty()) {
            ConfirmDialog(
                stringResource(R.string.backup_restore_title),
                stringResource(R.string.backup_nothing_changes, b.createdAt?.take(10) ?: "?"),
                stringResource(R.string.ok),
                onDismiss = { pending = null },
            ) { pending = null }
        } else {
            ConfirmDialog(
                stringResource(R.string.backup_restore_title),
                stringResource(R.string.backup_restore_body, b.createdAt?.take(10) ?: "?", names.joinToString("\n") { "• $it" }),
                stringResource(R.string.backup_restore_confirm),
                onDismiss = { pending = null },
            ) {
                pending = null
                scope.launch {
                    // Read again at the moment of writing, so nothing changed in between is lost.
                    c.settings.update { cur -> SettingsBackups.applied(cur, b.settings) }
                    Toast.makeText(context, R.string.backup_restored, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

private const val MAX_BYTES = 256 * 1024
