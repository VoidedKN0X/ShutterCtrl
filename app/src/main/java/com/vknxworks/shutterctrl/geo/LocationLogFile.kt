package com.vknxworks.shutterctrl.geo

import com.vknxworks.shutterctrl.data.crypto.DataKeyStore
import com.vknxworks.shutterctrl.data.crypto.SealedDataException
import com.vknxworks.shutterctrl.data.crypto.SealedFile
import java.io.File

/**
 * The location log on disk (`location-log.csv` in [dir]), encrypted at rest with the app's data key ([keys],
 * [SealedFile]). Slow the first time (it asks the phone's secure hardware for the key): never call it on the
 * main thread.
 *
 * - A sealed log is opened with the data key. An ordinary log left by a version before encryption is read
 *   as it is and then sealed where it lies ([SealedFile.sealInPlace]: checked copy, then one rename).
 * - Without a usable data key the log is read and written as an ordinary file, as before.
 * - The log is a short-lived working file (24 hours of positions), not something to keep: a sealed log that
 *   can't be opened (the key is gone, or the file is damaged) is deleted and a new log starts.
 */
class LocationLogFile(dir: File, private val keys: DataKeyStore) {
    private val file = File(dir, NAME)
    private val tmp = File(dir, "$NAME.tmp")

    /** The saved log's text, or null when there is none (or it couldn't be opened). */
    fun read(): String? {
        if (!file.exists()) return null
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        if (SealedFile.isSealed(bytes)) {
            val key = keys.get(create = false)
            val plain = if (key == null) {
                null
            } else {
                try {
                    SealedFile.open(key, bytes)
                } catch (e: SealedDataException) {
                    null
                }
            }
            if (plain == null) {
                file.delete()
                return null
            }
            return plain.decodeToString()
        }
        val key = keys.get(create = true)
        if (key != null) SealedFile.sealInPlace(key, file, tmp)
        return bytes.decodeToString()
    }

    /** Saves [text] as the log: sealed when there is a data key, as an ordinary file otherwise. */
    fun write(text: String) {
        val plain = text.toByteArray(Charsets.UTF_8)
        val key = keys.get(create = true)
        val out = (if (key != null) SealedFile.sealChecked(key, plain) else null) ?: plain
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    /** Forgets the log (the option was turned off). */
    fun delete() {
        file.delete()
        tmp.delete()
    }

    companion object {
        const val NAME = "location-log.csv"
    }
}
