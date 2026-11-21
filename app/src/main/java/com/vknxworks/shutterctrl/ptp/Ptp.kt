package com.vknxworks.shutterctrl.ptp

/**
 * PTP and Canon EOS codes ShutterCtrl uses.
 *
 * "Canon app" means the code was seen in Canon Camera Connect's own PTP layer (libEDSDKCore.so,
 * CPtpInterface::DS_* functions); "libgphoto2" means it comes from libgphoto2's ptp.h only.
 * See README "Protocol" for the full list.
 */
object Op {
    // Standard PTP (ISO 15740)
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009 // also in the Canon app, as the fallback in DS_GetObject
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val SEND_OBJECT_INFO = 0x100C
    const val SEND_OBJECT = 0x100D
    const val GET_PARTIAL_OBJECT = 0x101B

    // Canon EOS (all seen in the Canon app)
    const val EOS_GET_PARTIAL_OBJECT = 0x9107
    const val EOS_SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val EOS_SET_REMOTE_MODE = 0x9114
    const val EOS_SET_EVENT_MODE = 0x9115
    const val EOS_GET_EVENT = 0x9116
    const val EOS_UPDATE_FIRMWARE = 0x911F
    const val EOS_REMOTE_RELEASE_ON = 0x9128
    const val EOS_REMOTE_RELEASE_OFF = 0x9129
    const val EOS_GET_VIEWFINDER_DATA = 0x9153

    // Canon EOS, libgphoto2 only (PTP_OC_CANON_EOS_DriveLens). Moves the focus a step while live view runs:
    // parameter 1..3 = near (small, medium, large), 0x8001..0x8003 = far. Offered only when the camera lists it.
    const val EOS_DRIVE_LENS = 0x9155

    // Canon EOS, libgphoto2 only. Each is used only when the camera lists it among its operations.
    /** PTP_OC_CANON_EOS_DeleteObject (one parameter, the handle): used when the standard DeleteObject isn't listed. */
    const val EOS_DELETE_OBJECT = 0x9105
    /** PTP_OC_CANON_EOS_BulbStart / BulbEnd (no parameters): libgphoto2's "bulb" setting; the camera must be on Bulb. */
    const val EOS_BULB_START = 0x9125
    const val EOS_BULB_END = 0x9126
}

object Rc {
    const val OK = 0x2001
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
    /** Canon "not ready yet" (seen in the Canon app's GetViewFinderData loop). */
    const val CANON_NOT_READY = 0xA102

    fun name(code: Int): String = "0x%04X".format(code)
}

/** Canon EOS device properties (all seen in the Canon app's CPtpCamera setters). */
object Prop {
    const val AV = 0xD101
    const val TV = 0xD102
    const val ISO = 0xD103
    const val EXP_COMP = 0xD104
    const val BATTERY = 0xD111
    const val MODEL_ID = 0xD116 // libgphoto2 name; the Canon app uses 0xD116 but its meaning there is not confirmed
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B1

    // libgphoto2 only (ptp.h PTP_DPC_CANON_EOS_*; value tables from its config.c). Shown only when the camera
    // reports the property and the values it allows.
    const val DRIVE_MODE = 0xD106
    /** PTP_DPC_CANON_EOS_FocusMode: the AF mode (One Shot, AI Servo, AI Focus, Manual). */
    const val AF_MODE = 0xD108
    const val WHITE_BALANCE = 0xD109
    const val PICTURE_STYLE = 0xD110
    /**
     * PTP_DPC_CANON_EOS_EVFRecordStatus, libgphoto2's "movierecordtarget": 4 (Card) starts recording a movie
     * while live view runs, 0 (None) stops it.
     */
    const val EVF_RECORD_STATUS = 0xD1B8
}

/** libgphoto2's EVFRecordStatus values. */
object MovieRecord {
    const val STOP = 0L
    const val CARD = 4L
}

/** Canon EOS event records inside GetEvent data (libgphoto2 names). */
object Ev {
    const val OBJECT_ADDED_EX = 0xC181
    const val PROP_VALUE_CHANGED = 0xC189
    const val AVAIL_LIST_CHANGED = 0xC18A
    const val OBJECT_ADDED_EX64 = 0xC1A7
}

/** Object formats ShutterCtrl cares about. */
object Fmt {
    const val ASSOCIATION = 0x3001
    const val JPEG = 0x3801
    const val CANON_CRW = 0xB101
    const val CANON_CR2 = 0xB103
    const val CANON_CR3 = 0xB108
}

open class PtpException(message: String, val code: Int = 0) : Exception(message)

/** The USB cable was pulled (or the camera turned off) while connected over USB. */
class UsbDetachedException : PtpException("The USB cable was disconnected")

/** The camera's USB interface couldn't be opened (no permission, or another app holds it). */
class UsbOpenException(message: String) : PtpException(message)
