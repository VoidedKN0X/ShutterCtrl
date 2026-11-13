package com.vknxworks.shutterctrl.ptp

import kotlin.math.abs

/**
 * Canon exposure codes as stops. Canon codes count eighths of a stop, with thirds written as 3 and 5 and a
 * half as 4 (the same layout [ExposureValues] reads).
 */
object CanonStops {
    /** An unsigned code (Tv, Av, ISO) in stops. */
    fun stops(code: Int): Double {
        val whole = code / 8
        val frac = when (code % 8) {
            0 -> 0.0
            3 -> 1.0 / 3
            4 -> 0.5
            5 -> 2.0 / 3
            else -> (code % 8) / 8.0
        }
        return whole + frac
    }

    /** Exposure compensation (a signed byte) in stops. */
    fun expComp(code: Long): Double {
        val v = code.toInt().toByte().toInt()
        val s = stops(abs(v))
        return if (v < 0) -s else s
    }

    /** Shutter codes that are real times (not Auto 0x00 or Bulb 0x0C). */
    fun isTimedShutter(code: Long): Boolean = code != 0L && code != 0x0CL && code != 0xFFFFFFFFL
}

/** How a bracketing series changes the exposure. */
enum class BracketMethod(val prop: Int) {
    /** Exposure compensation (P, Av, Tv modes, and M with auto ISO). */
    EXP_COMP(Prop.EXP_COMP),
    /** Shutter speed (M mode, when the camera offers no exposure compensation). */
    SHUTTER(Prop.TV),
}

/** One shot of a series: the code to set, and how far it is from the starting exposure in stops (+ is brighter). */
data class BracketShot(val code: Long, val offsetEv: Double)

data class BracketPlan(
    val method: BracketMethod,
    val shots: List<BracketShot>,
    /** The value to put back afterwards. */
    val restore: Long,
    /** Some shots couldn't reach their exposure (the camera's range ended); they use the nearest value it has. */
    val clipped: Boolean,
)

/**
 * Exposure bracketing: N shots around the current exposure, in the order 0, −1, +1, −2, +2… steps.
 * Only values the camera says it accepts right now are used; shots that would repeat a value are left out.
 */
object Bracketing {
    val SHOT_COUNTS = listOf(3, 5, 7, 9)

    /** Step sizes in thirds of a stop: ⅓, ⅔, 1 and 2 stops. */
    val STEP_THIRDS = listOf(1, 2, 3, 6)

    /** The method this camera allows right now, or null (bracketing is then hidden). */
    fun method(props: Map<Int, Long>, allowed: Map<Int, List<Long>>): BracketMethod? {
        val ev = allowed[Prop.EXP_COMP].orEmpty()
        if (props[Prop.EXP_COMP] != null && ev.size >= 2) return BracketMethod.EXP_COMP
        val tv = allowed[Prop.TV].orEmpty().filter(CanonStops::isTimedShutter)
        val cur = props[Prop.TV]
        if (cur != null && CanonStops.isTimedShutter(cur) && tv.size >= 2) return BracketMethod.SHUTTER
        return null
    }

    /** Offsets in stops: 0, −s, +s, −2s, +2s… for [count] shots. */
    fun offsets(count: Int, stepThirds: Int): List<Double> {
        require(count >= 1 && stepThirds >= 1)
        val step = stepThirds / 3.0
        val out = arrayListOf(0.0)
        var k = 1
        while (out.size < count) {
            out += -k * step
            if (out.size < count) out += k * step
            k++
        }
        return out
    }

    fun plan(method: BracketMethod, current: Long, allowed: List<Long>, count: Int, stepThirds: Int): BracketPlan? {
        // Each allowed code with its brightness in stops (+ is brighter).
        val baseOf: (Long) -> Double
        val choices: List<Long>
        when (method) {
            BracketMethod.EXP_COMP -> {
                choices = allowed
                baseOf = CanonStops::expComp
            }
            BracketMethod.SHUTTER -> {
                if (!CanonStops.isTimedShutter(current)) return null
                choices = allowed.filter(CanonStops::isTimedShutter)
                // A larger Tv code is a faster shutter: darker.
                baseOf = { -CanonStops.stops(it.toInt()) }
            }
        }
        if (choices.isEmpty()) return null
        val base = baseOf(current)
        val shots = ArrayList<BracketShot>()
        var clipped = false
        for (off in offsets(count, stepThirds)) {
            val target = base + off
            val best = choices.minBy { abs(baseOf(it) - target) }
            val got = baseOf(best) - base
            if (abs(got - off) > 0.2) clipped = true
            if (shots.none { it.code == best }) shots += BracketShot(best, got)
        }
        return BracketPlan(method, shots, current, clipped)
    }
}

/**
 * Focus stacking with Canon's DriveLens during live view: a shot, then the focus moved [drivesPerShot] steps
 * of one size, then the next shot, and so on. The first shot is wherever focus is when you start.
 */
object FocusStack {
    val SHOT_COUNTS = listOf(5, 10, 15, 20, 30, 50)

    /** Step size, as libgphoto2 names DriveLens's parameter: 1 small, 2 medium, 3 large. */
    enum class Step(val level: Int) { SMALL(1), MEDIUM(2), LARGE(3) }

    sealed interface Action {
        data class Shoot(val index: Int) : Action
        data class Drive(val param: Int) : Action
    }

    /** DriveLens's parameter: 0x0001..0x0003 towards near, 0x8001..0x8003 towards far (libgphoto2). */
    fun driveParam(step: Step, towardFar: Boolean): Int = (if (towardFar) 0x8000 else 0) or step.level

    fun plan(shots: Int, step: Step, drivesPerShot: Int, towardFar: Boolean): List<Action> {
        require(shots >= 1 && drivesPerShot >= 1)
        val out = ArrayList<Action>()
        for (i in 0 until shots) {
            out += Action.Shoot(i)
            if (i < shots - 1) repeat(drivesPerShot) { out += Action.Drive(driveParam(step, towardFar)) }
        }
        return out
    }
}
