package com.vknxworks.shutterctrl.geo

import com.vknxworks.shutterctrl.data.crypto.DataKeyStore
import com.vknxworks.shutterctrl.data.crypto.KeyWrapper
import com.vknxworks.shutterctrl.data.crypto.SealedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The location log on disk: sealed when there is a data key, ordinary otherwise, and both kinds are read. */
class LocationLogFileTest {
    private val text = "1700000000000,50.9,3.1,12.0,5.0\n1700000030000,50.91,3.11,,"

    private fun tempDir(): File = Files.createTempDirectory("shutterctrl-log-test").toFile()

    /** Stands in for the Android Keystore: a wrap only its own [secret] can undo. */
    private class FakeWrapper(var secret: Byte = 7, var broken: Boolean = false) : KeyWrapper {
        override fun wrap(plain: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("keystore unavailable")
            return byteArrayOf(secret) + plain.map { (it + secret).toByte() }
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("keystore unavailable")
            if (wrapped[0] != secret) throw IllegalStateException("wrong key")
            return wrapped.drop(1).map { (it - secret).toByte() }.toByteArray()
        }
    }

    @Test
    fun theLogIsWrittenSealedAndReadBack() {
        val dir = tempDir()
        val log = LocationLogFile(dir, DataKeyStore(dir, FakeWrapper()))
        assertNull(log.read())
        log.write(text)
        val file = File(dir, LocationLogFile.NAME)
        assertTrue(SealedFile.isSealed(file))
        assertFalse(File(dir, LocationLogFile.NAME + ".tmp").exists())
        assertEquals(text, log.read())
        // A new start (the key comes from its file again).
        assertEquals(text, LocationLogFile(dir, DataKeyStore(dir, FakeWrapper())).read())
        log.delete()
        assertFalse(file.exists())
    }

    @Test
    fun anOrdinaryLogFromBeforeIsReadAndThenSealed() {
        val dir = tempDir()
        val file = File(dir, LocationLogFile.NAME).apply { writeText(text) }
        val log = LocationLogFile(dir, DataKeyStore(dir, FakeWrapper()))
        assertEquals(text, log.read())
        assertTrue(SealedFile.isSealed(file))
        assertEquals(text, log.read())
    }

    @Test
    fun withoutAKeyTheLogStaysAnOrdinaryFile() {
        val dir = tempDir()
        val log = LocationLogFile(dir, DataKeyStore(dir, FakeWrapper(broken = true)))
        log.write(text)
        val file = File(dir, LocationLogFile.NAME)
        assertFalse(SealedFile.isSealed(file))
        assertEquals(text, file.readText())
        assertEquals(text, log.read())
        assertFalse(File(dir, DataKeyStore.FILE).exists())
    }

    @Test
    fun aSealedLogThatCantBeOpenedIsDroppedAndTheKeyFileIsKept() {
        val dir = tempDir()
        LocationLogFile(dir, DataKeyStore(dir, FakeWrapper())).write(text)
        val keyFile = File(dir, DataKeyStore.FILE).readBytes()
        // The Keystore key is gone (a different one answers).
        val log = LocationLogFile(dir, DataKeyStore(dir, FakeWrapper(secret = 9)))
        assertNull(log.read())
        assertFalse(File(dir, LocationLogFile.NAME).exists())
        assertTrue(keyFile.contentEquals(File(dir, DataKeyStore.FILE).readBytes()))
        // From then on the log is an ordinary file: the key file is never replaced.
        log.write(text)
        assertFalse(SealedFile.isSealed(File(dir, LocationLogFile.NAME)))
        assertEquals(text, log.read())
    }

    @Test
    fun mergeAddsTheSavedFixesToTheOnesAlreadyThere() {
        val t = LocationTrack()
        val now = 1700000060000L
        t.add(Fix(1700000015000L, 51.0, 4.0), now)
        t.merge(text, now)
        assertEquals(3, t.size)
        assertEquals(50.9, t.match(1700000000000L)!!.lat, 0.0)
        assertEquals(51.0, t.match(1700000015000L)!!.lat, 0.0)
        assertEquals(3.11, t.match(1700000030000L)!!.lon, 0.0)
        t.merge("", now)
        assertEquals(3, t.size)
    }
}
