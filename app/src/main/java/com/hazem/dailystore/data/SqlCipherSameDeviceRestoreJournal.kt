package com.hazem.dailystore.data

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Durable same-device restore journal. It contains filenames and phases only. */
internal class SqlCipherSameDeviceRestoreJournal(file: File) {
    private val atomic = AtomicFile(file)

    enum class Phase { PREPARED, OLD_DATABASE_BACKED_UP, KEY_REPLACED, DATABASE_SWAPPED, VERIFIED }

    data class Record(
        val phase: Phase,
        val stagingName: String,
        val liveName: String,
        val rollbackName: String,
        val keySnapshotName: String
    )

    @Synchronized
    fun begin(staging: File, live: File, rollback: File, keySnapshot: File) {
        check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            "يوجد سجل استعادة سابق؛ يجب التعافي منه أولاً"
        }
        val parent = live.canonicalFile.parentFile
        require(parent != null && staging.canonicalFile.parentFile == parent &&
            rollback.canonicalFile.parentFile == parent && staging.canonicalFile != live.canonicalFile &&
            rollback.canonicalFile != live.canonicalFile && rollback.canonicalFile != staging.canonicalFile)
        require(keySnapshot.name.isNotBlank() && File(keySnapshot.name).name == keySnapshot.name)
        write(Record(Phase.PREPARED, staging.name, live.name, rollback.name, keySnapshot.name))
    }

    @Synchronized
    fun advance(expected: Phase, next: Phase) {
        require(next.ordinal == expected.ordinal + 1) { "Invalid same-device restore phase transition" }
        val current = read() ?: error("Same-device restore journal is missing")
        check(current.phase == expected) { "Same-device restore phase changed unexpectedly" }
        write(current.copy(phase = next))
    }

    @Synchronized
    fun read(): Record? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        try {
            val input = DataInputStream(ByteArrayInputStream(atomic.openRead().use { it.readBytes() }))
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC) && input.readInt() == VERSION)
            val ordinal = input.readInt()
            require(ordinal in Phase.values().indices)
            val names = List(4) { input.readUTF() }
            require(input.read() == -1)
            names.forEach(::validateName)
            require(names.toSet().size == names.size)
            return Record(Phase.values()[ordinal], names[0], names[1], names[2], names[3])
        } catch (error: Exception) {
            throw IllegalStateException(
                "سجل استعادة SQLCipher على الجهاز تالف؛ احتفظ بكل الملفات وأوقف الاستعادة",
                error
            )
        }
    }

    @Synchronized
    fun clear() = atomic.delete()

    private fun write(record: Record) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(record.phase.ordinal)
                out.writeUTF(record.stagingName)
                out.writeUTF(record.liveName)
                out.writeUTF(record.rollbackName)
                out.writeUTF(record.keySnapshotName)
            }
        }.toByteArray()
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw IllegalStateException("تعذر حفظ سجل الاستعادة الآمن", error)
        }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            File(name).name == name && !name.contains('/') && !name.contains('\\'))
    }

    companion object {
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x53, 0x44, 0x52, 0x4A, 0x31) // TDSSDRJ1
        private const val VERSION = 1
    }
}
