package com.vknxworks.shutterctrl.ui.camera

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.vknxworks.shutterctrl.ptp.ExposureValues
import com.vknxworks.shutterctrl.ptp.Prop
import com.vknxworks.shutterctrl.ui.components.ChoiceSheet
import com.vknxworks.shutterctrl.ui.components.CtrlCard
import com.vknxworks.shutterctrl.ui.components.SectionLabel
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** One exposure setting the camera can change remotely. */
private data class Setting(val prop: Int, val label: Int, val format: (Long) -> String)

private val settingsList = listOf(
    Setting(Prop.AV, R.string.ctl_av, ExposureValues::aperture),
    Setting(Prop.TV, R.string.ctl_tv, ExposureValues::shutter),
    Setting(Prop.ISO, R.string.ctl_iso, ExposureValues::iso),
    Setting(Prop.EXP_COMP, R.string.ctl_ev, ExposureValues::expComp),
)

/**
 * Av, Tv, ISO and exposure compensation as four tiles. Values and choices come from the camera's own
 * events; a tile without choices can't be changed in the camera's current mode (or the camera hasn't said).
 */
@Composable
fun ExposureRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = context.container.session
    val scope = rememberCoroutineScope()
    val props by session.props.collectAsStateWithLifecycle()
    val allowed by session.allowed.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Setting?>(null) }

    Row(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        settingsList.forEach { s ->
            val v = props[s.prop]
            val choices = allowed[s.prop].orEmpty()
            CtrlCard(
                Modifier.weight(1f),
                onClick = {
                    if (choices.isEmpty()) Toast.makeText(context, context.getString(R.string.ctl_no_choices), Toast.LENGTH_SHORT).show()
                    else open = s
                },
                padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            ) {
                SectionLabel(stringResource(s.label))
                Spacer(Modifier.size(4.dp))
                Text(
                    if (v == null) "--" else s.format(v),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (choices.isEmpty()) CtrlColors.Muted else CtrlColors.Text,
                    maxLines = 1,
                )
            }
        }
    }
    open?.let { s ->
        val choices = allowed[s.prop].orEmpty()
        ChoiceSheet(
            stringResource(s.label),
            choices.map { it to s.format(it) },
            props[s.prop],
            onDismiss = { open = null },
        ) { value ->
            open = null
            scope.launch {
                runCatching { session.setProp(s.prop, value) }.onFailure {
                    Toast.makeText(context, context.getString(R.string.ctl_set_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
