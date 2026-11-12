package com.vknxworks.shutterctrl.camera

import com.vknxworks.shutterctrl.ptp.CanonData
import com.vknxworks.shutterctrl.ptp.ExposureValues
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The safety checks before a firmware file goes to the camera. Pure logic, unit-tested.
 * The camera checks the file itself again before it installs anything, and installing is always
 * started by you on the camera.
 */
object FirmwareRules {
    const val MIN_SIZE = 64 * 1024L
    const val MAX_SIZE = 512L * 1024 * 1024

    enum class Route { CANON_TRANSFER, COPY_TO_CARD, NONE }

    enum class Block { NOT_FIR, BAD_SIZE, NAME_TOO_LONG, BATTERY_UNKNOWN, BATTERY_LOW, MODEL_MISMATCH, NO_ROUTE }

    /** Model id in a Canon .FIR file header: the first 4 bytes, little-endian. */
    fun firModelId(header: ByteArray): Long? =
        if (header.size < 4) null else ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    fun route(supportsCanonUpdate: Boolean, supportsSendObject: Boolean): Route = when {
        supportsCanonUpdate -> Route.CANON_TRANSFER
        supportsSendObject -> Route.COPY_TO_CARD
        else -> Route.NONE
    }

    data class Check(val blocks: List<Block>, val modelConfirmed: Boolean)

    fun check(
        fileName: String,
        size: Long,
        firModelId: Long?,
        cameraModelId: Long?,
        battery: Long?,
        route: Route,
    ): Check {
        val blocks = ArrayList<Block>()
        if (!fileName.endsWith(".fir", ignoreCase = true)) blocks += Block.NOT_FIR
        if (size !in MIN_SIZE..MAX_SIZE) blocks += Block.BAD_SIZE
        val ascii = fileName.all { it.code in 0x21..0x7E }
        if (!ascii || fileName.toByteArray(Charsets.US_ASCII).size >= CanonData.FIRMWARE_NAME_FIELD) blocks += Block.NAME_TOO_LONG
        val pct = battery?.let { ExposureValues.batteryPercent(it) }
        when {
            pct == null -> blocks += Block.BATTERY_UNKNOWN
            pct < 100 -> blocks += Block.BATTERY_LOW
        }
        if (firModelId != null && cameraModelId != null && firModelId != cameraModelId) blocks += Block.MODEL_MISMATCH
        if (route == Route.NONE) blocks += Block.NO_ROUTE
        return Check(blocks, firModelId != null && cameraModelId != null && firModelId == cameraModelId)
    }
}
