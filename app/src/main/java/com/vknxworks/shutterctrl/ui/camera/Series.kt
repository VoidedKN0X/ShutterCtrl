package com.vknxworks.shutterctrl.ui.camera

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.CameraSession
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.container
import com.vknxworks.shutterctrl.data.settings.AppSettings
import com.vknxworks.shutterctrl.ptp.BracketMethod
import com.vknxworks.shutterctrl.ptp.Bracketing
import com.vknxworks.shutterctrl.ptp.FocusStack
import com.vknxworks.shutterctrl.ui.components.CtrlButton
import com.vknxworks.shutterctrl.ui.components.CtrlProgressBar
import com.vknxworks.shutterctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.shutterctrl.ui.components.Group
import com.vknxworks.shutterctrl.ui.components.GroupChoice
import com.vknxworks.shutterctrl.ui.theme.CtrlColors
import com.vknxworks.shutterctrl.ui.theme.CtrlType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** "+1⅓", "−⅔", "0": a number of stops in thirds, the way cameras print it. */
private fun evText(stops: Double): String {
    val thirds = (stops * 3).roundToInt()
    if (thirds == 0) return "0"
    val sign = if (thirds < 0) "−" else "+"
    val a = abs(thirds)
    val whole = a / 3
    val frac = when (a % 3) { 1 -> "⅓"; 2 -> "⅔"; else -> "" }
    return sign + (if (whole == 0) "" else whole.toString()) + frac
}

private val STEP_LABELS = mapOf(1 to "⅓ EV", 2 to "⅔ EV", 3 to "1 EV", 6 to "2 EV")

/** The progress of a running (or just finished) series, with Stop. */
@Composable
private fun SeriesProgress(s: CameraSession.Series, onStop: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.series_progress, s.done, s.total), style = MaterialTheme.typography.titleMedium)
        CtrlProgressBar(s.done.toFloat() / s.total.coerceAtLeast(1), color = CtrlColors.Accent)
        if (s.error != null) Text(stringResource(R.string.series_failed, s.done), style = CtrlType.Data, color = CtrlColors.Error)
        if (s.finished) {
            if (s.error == null) Text(stringResource(R.string.series_done, s.done), style = CtrlType.Data, color = CtrlColors.Muted)
            CtrlSecondaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.series_ok)) }
        } else {
            CtrlButton(stringResource(R.string.stop), onClick = onStop, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Exposure bracketing on the Camera tab: N shots at ± steps around the current exposure. Only shown when the
 * camera reports exposure compensation (or, in M mode, shutter speed) values it accepts right now.
 */
@Composable
fun BracketGroup() {
    val context = LocalContext.current
    val c = context.container
    val session = c.session
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val props by session.props.collectAsStateWithLifecycle()
    val allowed by session.allowed.collectAsStateWithLifecycle()
    val series by session.series.collectAsStateWithLifecycle()
    val interval by IntervalService.state.collectAsStateWithLifecycle()
    val running = series?.kind == CameraSession.Series.Kind.BRACKET
    val method = if (session.canSetProps) Bracketing.method(props, allowed) else null
    if (method == null && !running) return
    fun set(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }

    Group(stringResource(R.string.group_bracket), footer = stringResource(R.string.bracket_footer)) {
        val s = series
        if (s != null && running) {
            SeriesProgress(s, onStop = { session.stopSeries() }, onDone = { session.clearSeries() })
            return@Group
        }
        if (method == null) return@Group
        GroupChoice(
            stringResource(R.string.bracket_shots),
            Bracketing.SHOT_COUNTS.map { it to it.toString() },
            settings.bracketShots,
            icon = Icons.Outlined.Layers,
        ) { v -> set { it.copy(bracketShots = v) } }
        GroupChoice(
            stringResource(R.string.bracket_step),
            Bracketing.STEP_THIRDS.map { it to STEP_LABELS.getValue(it) },
            settings.bracketStepThirds,
            icon = Icons.Outlined.Exposure,
        ) { v -> set { it.copy(bracketStepThirds = v) } }
        val current = props[method.prop]
        val plan = current?.let { Bracketing.plan(method, it, allowed[method.prop].orEmpty(), settings.bracketShots, settings.bracketStepThirds) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan != null) {
                Text(
                    stringResource(
                        if (method == BracketMethod.EXP_COMP) R.string.bracket_plan_ev else R.string.bracket_plan_tv,
                        plan.shots.joinToString("  ") { evText(it.offsetEv) },
                    ),
                    style = CtrlType.Data,
                )
                if (plan.clipped) Text(stringResource(R.string.bracket_clipped), style = CtrlType.Data, color = CtrlColors.Muted)
            }
            CtrlButton(
                stringResource(R.string.bracket_start, plan?.shots?.size ?: settings.bracketShots),
                onClick = {
                    val p = plan ?: return@CtrlButton
                    if (p.shots.size < 2) {
                        Toast.makeText(context, context.getString(R.string.bracket_no_range), Toast.LENGTH_SHORT).show()
                        return@CtrlButton
                    }
                    session.startBracket(p)
                },
                enabled = plan != null && !interval.active && (s == null || s.finished),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Focus stacking in live view: moves the focus with Canon's DriveLens between shots. Only shown when the
 * camera lists DriveLens (and live view works).
 */
@Composable
fun FocusStackGroup() {
    val context = LocalContext.current
    val c = context.container
    val session = c.session
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val series by session.series.collectAsStateWithLifecycle()
    val interval by IntervalService.state.collectAsStateWithLifecycle()
    if (!session.canFocusStack) return
    fun set(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }
    val step = FocusStack.Step.entries.first { it.level == settings.stackStep }
    val running = series?.kind == CameraSession.Series.Kind.FOCUS_STACK

    Group(stringResource(R.string.group_stack), footer = stringResource(R.string.stack_footer)) {
        val s = series
        if (s != null && running) {
            SeriesProgress(s, onStop = { session.stopSeries() }, onDone = { session.clearSeries() })
            return@Group
        }
        GroupChoice(
            stringResource(R.string.stack_shots),
            FocusStack.SHOT_COUNTS.map { it to it.toString() },
            settings.stackShots,
            icon = Icons.Outlined.Layers,
        ) { v -> set { it.copy(stackShots = v) } }
        GroupChoice(
            stringResource(R.string.stack_step),
            listOf(1 to stringResource(R.string.stack_step_small), 2 to stringResource(R.string.stack_step_medium), 3 to stringResource(R.string.stack_step_large)),
            settings.stackStep,
            icon = Icons.Outlined.LinearScale,
        ) { v -> set { it.copy(stackStep = v) } }
        GroupChoice(
            stringResource(R.string.stack_drives),
            listOf(1, 2, 3, 5, 8, 12, 20).map { it to it.toString() },
            settings.stackDrives,
            icon = Icons.Outlined.FilterCenterFocus,
        ) { v -> set { it.copy(stackDrives = v) } }
        GroupChoice(
            stringResource(R.string.stack_direction),
            listOf(true to stringResource(R.string.stack_toward_far), false to stringResource(R.string.stack_toward_near)),
            settings.stackTowardFar,
            icon = Icons.Outlined.SwapHoriz,
        ) { v -> set { it.copy(stackTowardFar = v) } }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Nudge the focus to the starting point first.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(false to R.string.stack_nudge_near, true to R.string.stack_nudge_far).forEach { (far, label) ->
                    CtrlSecondaryButton(
                        onClick = {
                            scope.launch {
                                runCatching { session.driveLens(FocusStack.driveParam(step, far)) }.onFailure {
                                    Toast.makeText(context, context.getString(R.string.stack_drive_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = s == null || s.finished,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(label)) }
                }
            }
            Text(
                stringResource(R.string.stack_summary, settings.stackShots, settings.stackDrives * (settings.stackShots - 1)),
                style = CtrlType.Data,
                color = CtrlColors.Muted,
            )
            CtrlButton(
                stringResource(R.string.stack_start, settings.stackShots),
                onClick = { session.startFocusStack(FocusStack.plan(settings.stackShots, step, settings.stackDrives, settings.stackTowardFar)) },
                enabled = !interval.active && (s == null || s.finished),
                icon = Icons.Outlined.CenterFocusWeak,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
