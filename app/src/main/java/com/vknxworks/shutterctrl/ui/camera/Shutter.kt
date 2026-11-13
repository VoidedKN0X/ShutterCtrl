package com.vknxworks.shutterctrl.ui.camera

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.shutterctrl.ui.components.GroupChoice
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.Gutter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Focus (half press, held until tapped again) and Shoot (with the self-timer). Disabled while the
 * intervalometer runs.
 */
@Composable
fun ShutterButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val session = c.session
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val interval by IntervalService.state.collectAsStateWithLifecycle()
    // A bracketing or focus-stacking series has the shutter.
    val series by session.series.collectAsStateWithLifecycle()
    val seriesBusy = series?.finished == false
    var focusing by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    fun failed() = Toast.makeText(context, context.getString(R.string.shoot_failed), Toast.LENGTH_SHORT).show()

    Row(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlSecondaryButton(
            onClick = {
                scope.launch {
                    val on = !focusing
                    runCatching { session.halfPress(on) }.onSuccess { focusing = on }.onFailure { focusing = false; failed() }
                }
            },
            enabled = !busy && !interval.active && !seriesBusy,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.CenterFocusStrong, null, tint = if (focusing) CtrlColors.AccentText else CtrlColors.Text)
            Text(
                stringResource(if (focusing) R.string.focus_release else R.string.focus),
                modifier = Modifier.padding(start = 8.dp),
                color = if (focusing) CtrlColors.AccentText else CtrlColors.Text,
            )
        }
        CtrlButton(
            when {
                countdown > 0 -> stringResource(R.string.shoot_in, countdown)
                busy -> stringResource(R.string.shooting)
                else -> stringResource(R.string.shoot)
            },
            onClick = {
                if (countdown > 0) {
                    timerJob?.cancel(); countdown = 0; busy = false
                    return@CtrlButton
                }
                timerJob = scope.launch {
                    busy = true
                    try {
                        for (s in settings.selfTimer downTo 1) {
                            countdown = s
                            delay(1000)
                        }
                        countdown = 0
                        if (focusing) runCatching { session.halfPress(false) }
                        focusing = false
                        runCatching { session.shoot() }.onFailure { failed() }
                    } finally {
                        countdown = 0
                        busy = false
                    }
                }
            },
            enabled = (!busy || countdown > 0) && !interval.active && !seriesBusy,
            icon = if (settings.selfTimer > 0) Icons.Outlined.Timer else null,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** The self-timer choice as a settings row. */
@Composable
fun SelfTimerRow() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    GroupChoice(
        stringResource(R.string.self_timer),
        listOf(0 to stringResource(R.string.timer_off), 2 to "2 s", 10 to "10 s"),
        settings.selfTimer,
        icon = Icons.Outlined.Timer,
    ) { v -> scope.launch { c.settings.update { it.copy(selfTimer = v) } } }
}
