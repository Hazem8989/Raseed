package com.hazem.dailystore.data

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Durable, key-free progress record for the SQLCipher restore transaction.
 *
 * The journal stores only the phase and two same-directory file names. It never
 * stores a database key, password, or absolute path. A later recovery coordinator
 * must validate the referenced encrypted database with SQLCipher before deciding
 * whether to finish or roll back a transaction.
 */
internal class SqlCipherRestoreJournal(journalFile: File) {
    private val atomicFile = AtomicFile(journalFile)

    data class Record(
        val phase: Phase,
        val stagingName: String,
        val destinationName: String
    )

    enum class Phase {
        STAGED,
        KEY_IMPORTED,
        DATABASE_COMMITTED
    }

    @Synchronized
    fun begin(stagingFile: File, destinationFile: File) {
        require(!atomicFile.baseFile.exists() && !File(atomicFile.baseFile.path + ".bak").exists()) {
            "يوجد سجل استعادة سابق؛ يجب التعافي منه قبل بدء استعادة جديدة"
        }
        val names = validatePair(stagingFile, destinationFile)
        writeRecord(Record(Phase.STAGED, names.first, names.second))
    }

    @Synchronized
    fun advance(expected: Phase, next: Phase) {
        require(next.ordinal == expected.ordinal + 1) { "Invalid restore-journal phase transition" }
        val current = read() ?: throw IllegalStateException("Restore journal is missing")
        check(current.phase == expected) { "Restore journal phase changed unexpectedly" }
        writeRecord(current.copy(phase = next))
    }

    @Synchronized
    fun read(): Record? {
        if (!atomicFile.baseFile.exists() && !File(atomicFile.baseFile.path + ".bak").exists()) {
            return null
        }
        try {
            val bytes = atomicFile.openRead().use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Invalid SQLCipher restore journal" }
            require(input.readInt() == VERSION) { "Unsupported SQLCipher restore journal version" }
            val phaseOrdinal = input.readInt()
            require(phaseOrdinal in Phase.entries.indices) { "Invalid SQLCipher restore phase" }
            val staging = input.readUTF()
            val destination = input.readUTF()
            require(input.read() == -1) { "Unexpected trailing restore-journal data" }
            validateName(staging)
            validateName(destination)
            require(staging != destination) { "Restore journal paths must be distinct" }
            return Record(Phase.entries[phaseOrdinal], staging, destination)
        } catch (error: Exception) {
            throw IllegalStateException(
                "سجل الاستعادة تالف؛ أوقف الاستعادة واحتفظ بملفات قاعدة البيانات دون حذفها",
                error
            )
        }
    }

    @Synchronized
    fun clear() {
        atomicFile.delete()
    }

    private fun writeRecord(record: Record) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(record.phase.ordinal)
                output.writeUTF(record.stagingName)
                output.writeUTF(record.destinationName)
            }
        }.toByteArray()
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomicFile::failWrite)
            throw IllegalStateException("تعذر حفظ تقدم عملية الاستعادة بأمان", error)
        }
    }

    private fun validatePair(staging: File, destination: File): Pair<String, String> {
        val stagingParent = staging.canonicalFile.parentFile
        val destinationParent = destination.canonicalFile.parentFile
        require(stagingParent != null && stagingParent == destinationParent) {
            "يجب أن يكون ملف التجهيز والوجهة في المجلد نفسه"
        }
        val stagingName = staging.name
        val destinationName = destination.name
        validateName(stagingName)
        validateName(destinationName)
        require(stagingName != destinationName)
        return stagingName to destinationName
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            File(name).name == name && !name.contains('/') && !name.contains('\\')) {
            "Invalid restore-journal file name"
        }
    }

    companion object {
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x52, 0x53, 0x54, 0x4A, 0x31) // TDSRSTJ1
        private const val VERSION = 1
    }
}
