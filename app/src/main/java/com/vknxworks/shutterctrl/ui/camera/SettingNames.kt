package com.vknxworks.shutterctrl.ui.camera

import com.vknxworks.shutterctrl.R

/**
 * Names for Canon's codes of white balance (0xD109), drive mode (0xD106), AF mode (0xD108) and picture style
 * (0xD110), from libgphoto2's tables (camlibs/ptp2/config.c: canon_eos_whitebalance, canon_eos_drive_mode,
 * eos_focusmodes, canon_eos_picturestyle). A code that isn't listed is shown as its number.
 */
internal object SettingNames {
    val whiteBalance: Map<Long, Int> = mapOf(
        0L to R.string.wb_auto,
        1L to R.string.wb_daylight,
        2L to R.string.wb_cloudy,
        3L to R.string.wb_tungsten,
        4L to R.string.wb_fluorescent,
        5L to R.string.wb_flash,
        6L to R.string.wb_custom,
        8L to R.string.wb_shade,
        9L to R.string.wb_kelvin,
        23L to R.string.wb_auto_white,
    )

    val driveMode: Map<Long, Int> = mapOf(
        0x00L to R.string.drive_single,
        0x01L to R.string.drive_continuous,
        0x04L to R.string.drive_continuous_high,
        0x05L to R.string.drive_continuous_low,
        0x06L to R.string.drive_single_silent,
        0x07L to R.string.drive_continuous_timer,
        0x10L to R.string.drive_timer_10,
        0x11L to R.string.drive_timer_2,
        0x12L to R.string.drive_super_high,
        0x13L to R.string.drive_single_silent,
        0x14L to R.string.drive_continuous_silent,
        0x15L to R.string.drive_silent_high,
        0x16L to R.string.drive_silent_low,
    )

    val afMode: Map<Long, Int> = mapOf(
        0L to R.string.af_one_shot,
        1L to R.string.af_servo,
        2L to R.string.af_ai_focus,
        3L to R.string.af_manual,
    )

    val pictureStyle: Map<Long, Int> = mapOf(
        0x81L to R.string.style_standard,
        0x82L to R.string.style_portrait,
        0x83L to R.string.style_landscape,
        0x84L to R.string.style_neutral,
        0x85L to R.string.style_faithful,
        0x86L to R.string.style_monochrome,
        0x87L to R.string.style_auto,
        0x88L to R.string.style_fine_detail,
        0x21L to R.string.style_user_1,
        0x22L to R.string.style_user_2,
        0x23L to R.string.style_user_3,
    )
}
