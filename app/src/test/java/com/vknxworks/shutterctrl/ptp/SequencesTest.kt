package com.vknxworks.shutterctrl.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequencesTest {
    /** Canon exposure compensation codes from −3 to +3 in thirds (signed bytes as unsigned longs). */
    private val evCodes: List<Long> = listOf(-24, -21, -19, -16, -13, -11, -8, -5, -3, 0, 3, 5, 8, 11, 13, 16, 19, 21, 24).map { (it and 0xFF).toLong() }

    private fun ev(code: Long) = CanonStops.expComp(code)

    @Test
    fun canonCodesToStops() {
        assertEquals(1.0 / 3, CanonStops.expComp(3), 1e-9)
        assertEquals(-(1 + 2.0 / 3), CanonStops.expComp(0xF3), 1e-9) // −13 eighths
        assertEquals(0.5, CanonStops.expComp(4), 1e-9)
        assertEquals(7.0, CanonStops.stops(0x38), 1e-9) // Tv 1 s
        assertEquals(7 + 1.0 / 3, CanonStops.stops(0x3B), 1e-9)
    }

    @Test
    fun offsetsGoZeroMinusPlus() {
        assertEquals(listOf(0.0, -1.0, 1.0), Bracketing.offsets(3, 3))
        assertEquals(listOf(0.0, -2.0 / 3, 2.0 / 3, -4.0 / 3, 4.0 / 3), Bracketing.offsets(5, 2))
        assertEquals(listOf(0.0, -2.0, 2.0, -4.0, 4.0, -6.0, 6.0), Bracketing.offsets(7, 6))
    }

    @Test
    fun exposureCompensationPlan() {
        val p = Bracketing.plan(BracketMethod.EXP_COMP, current = 0, allowed = evCodes, count = 5, stepThirds = 3)!!
        assertEquals(listOf(0.0, -1.0, 1.0, -2.0, 2.0), p.shots.map { ev(it.code) })
        assertEquals(listOf(0.0, -1.0, 1.0, -2.0, 2.0), p.shots.map { it.offsetEv })
        assertEquals(0L, p.restore)
        assertFalse(p.clipped)
        assertEquals(Prop.EXP_COMP, p.method.prop)
    }

    @Test
    fun planStartsFromTheCurrentValue() {
        // Current +⅔: thirds steps go +⅔, +⅓, +1.
        val p = Bracketing.plan(BracketMethod.EXP_COMP, current = 5, allowed = evCodes, count = 3, stepThirds = 1)!!
        assertEquals(listOf(5L, 3L, 8L), p.shots.map { it.code })
        assertEquals(5L, p.restore)
    }

    @Test
    fun theEndOfTheRangeClipsAndDropsRepeats() {
        // At +2⅔ with 1-stop steps: +2⅔, +1⅔, then +3 (clipped), −… +3 again would repeat and is left out.
        val p = Bracketing.plan(BracketMethod.EXP_COMP, current = 21, allowed = evCodes, count = 5, stepThirds = 3)!!
        assertTrue(p.clipped)
        assertEquals(p.shots.map { it.code }.distinct(), p.shots.map { it.code })
        assertEquals(21L, p.shots.first().code)
        assertTrue(p.shots.all { ev(it.code) <= 3.0 })
    }

    @Test
    fun shutterPlanInManualMode() {
        // Tv codes 1/15 (0x58) to 1/250 (0x78) in thirds: larger codes are faster.
        val tv = (0x58L..0x78L).filter { (it % 8).toInt() in listOf(0, 3, 5) }
        val p = Bracketing.plan(BracketMethod.SHUTTER, current = 0x68, allowed = tv, count = 3, stepThirds = 3)!!
        // 1/60 (0x68): −1 stop = 1/125 (0x70), +1 stop = 1/30 (0x60).
        assertEquals(listOf(0x68L, 0x70L, 0x60L), p.shots.map { it.code })
        assertEquals(listOf(0.0, -1.0, 1.0), p.shots.map { it.offsetEv })
        assertEquals(Prop.TV, p.method.prop)
    }

    @Test
    fun noShutterBracketingOnBulbOrAuto() {
        assertNull(Bracketing.plan(BracketMethod.SHUTTER, current = 0x0C, allowed = listOf(0x60, 0x68), count = 3, stepThirds = 3))
        assertNull(Bracketing.plan(BracketMethod.SHUTTER, current = 0, allowed = listOf(0x60, 0x68), count = 3, stepThirds = 3))
    }

    @Test
    fun methodFollowsWhatTheCameraReports() {
        assertEquals(BracketMethod.EXP_COMP, Bracketing.method(mapOf(Prop.EXP_COMP to 0L), mapOf(Prop.EXP_COMP to evCodes)))
        // M mode: no compensation values, but shutter speeds.
        assertEquals(BracketMethod.SHUTTER, Bracketing.method(mapOf(Prop.TV to 0x68L), mapOf(Prop.TV to listOf(0x60L, 0x68L, 0x70L))))
        // Nothing reported: hidden.
        assertNull(Bracketing.method(emptyMap(), emptyMap()))
        assertNull(Bracketing.method(mapOf(Prop.EXP_COMP to 0L), mapOf(Prop.EXP_COMP to listOf(0L))))
        assertNull(Bracketing.method(mapOf(Prop.TV to 0x0CL), mapOf(Prop.TV to listOf(0x0CL, 0x60L, 0x68L))))
    }

    @Test
    fun driveLensParameters() {
        assertEquals(0x0001, FocusStack.driveParam(FocusStack.Step.SMALL, towardFar = false))
        assertEquals(0x0003, FocusStack.driveParam(FocusStack.Step.LARGE, towardFar = false))
        assertEquals(0x8001, FocusStack.driveParam(FocusStack.Step.SMALL, towardFar = true))
        assertEquals(0x8002, FocusStack.driveParam(FocusStack.Step.MEDIUM, towardFar = true))
    }

    @Test
    fun focusStackPlan() {
        val plan = FocusStack.plan(shots = 3, step = FocusStack.Step.MEDIUM, drivesPerShot = 2, towardFar = true)
        assertEquals(
            listOf(
                FocusStack.Action.Shoot(0), FocusStack.Action.Drive(0x8002), FocusStack.Action.Drive(0x8002),
                FocusStack.Action.Shoot(1), FocusStack.Action.Drive(0x8002), FocusStack.Action.Drive(0x8002),
                FocusStack.Action.Shoot(2),
            ),
            plan,
        )
        assertEquals(listOf(FocusStack.Action.Shoot(0)), FocusStack.plan(1, FocusStack.Step.SMALL, 5, false))
        assertEquals(20 * 4, FocusStack.plan(21, FocusStack.Step.SMALL, 4, false).count { it is FocusStack.Action.Drive })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aStackNeedsAShot() {
        FocusStack.plan(0, FocusStack.Step.SMALL, 1, true)
    }
}
