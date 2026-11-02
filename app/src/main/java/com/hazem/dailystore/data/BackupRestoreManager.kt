package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted, authenticated, single-file database backup.
 *
 * The database is checkpointed and closed before it is copied so committed
 * WAL transactions are included. Restore decrypts and validates a staging
 * database before replacing the live file, and retains a rollback copy until
 * the restored database has opened and passed integrity checks.
 */
class BackupRestoreManager(private val context: Context) {
    fun createBackup(actorUserId: String, password: CharArray, destination: OutputStream, onProgress: ((String) -> Unit)? = null) {
        requireBackupPermission(actorUserId)
        validatePassword(password)
        onProgress?.invoke("إغلاق قاعدة البيانات وتجهيز لقطة آمنة...")
        synchronized(LOCK) {
            val snapshot = File(context.cacheDir, "tds-backup-snapshot-${System.nanoTime()}.db")
            val encrypted = File(context.cacheDir, "tds-backup-encrypted-${System.nanoTime()}.bin")
            try {
                AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                check(live.isFile && live.length() > 0L) { "قاعدة البيانات غير موجودة أو فارغة" }
                check(live.length() <= MAX_DATABASE_BYTES) { "حجم قاعدة البيانات يتجاوز الحد المدعوم للنسخ الاحتياطي" }
                onProgress?.invoke("نسخ قاعدة البيانات إلى ملف مؤقت...")
                copyFile(live, snapshot)
                onProgress?.invoke("تشفير النسخة وحمايتها...")
                encryptSnapshot(snapshot, encrypted, password)
                onProgress?.invoke("حفظ ملف النسخة المشفّرة...")
                FileInputStream(encrypted).use { input -> input.copyTo(destination) }
                destination.flush()
                onProgress?.invoke("اكتمل إنشاء النسخة الاحتياطية.")
            } finally {
                snapshot.delete()
                encrypted.delete()
            }
        }
    }

    fun restoreBackup(actorUserId: String, password: CharArray, source: InputStream, onProgress: ((String) -> Unit)? = null) {
        requireBackupPermission(actorUserId)
        validatePassword(password)
        onProgress?.invoke("فك تشفير النسخة والتحقق من كلمة المرور...")
        synchronized(LOCK) {
            val staged = File(context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile,
                "the_daily_shop.restore-${System.nanoTime()}.db")
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val rollback = File(live.parentFile, "${live.name}.restore-rollback")
            var liveWasMoved = false
            try {
                decryptToFile(source, staged, password)
                onProgress?.invoke("التحقق من سلامة قاعدة البيانات والعلاقات...")
                validateDatabase(staged)

                onProgress?.invoke("إغلاق قاعدة البيانات الحالية وحفظ نقطة التراجع...")
                AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                deleteSidecars(live)
                if (rollback.exists() && !rollback.delete()) {
                    throw IllegalStateException("تعذر إزالة ملف الاستعادة المؤقت السابق")
                }
                if (live.exists()) {
                    check(live.renameTo(rollback)) { "تعذر حفظ قاعدة البيانات الحالية للتراجع" }
                    liveWasMoved = true
                }
                onProgress?.invoke("استبدال قاعدة البيانات بالنسخة المتحقق منها...")
                check(staged.renameTo(live)) { "تعذر وضع قاعدة البيانات المستعادة" }

                try {
                    val restored = AppDatabase.getInstance(context).writableDatabase
                    validateOpenDatabase(restored)
                    onProgress?.invoke("اعتماد قاعدة البيانات المستعادة...")
                    rollback.delete()
                } catch (restoreFailure: Exception) {
                    AppDatabase.getInstance(context).close()
                    live.delete()
                    deleteSidecars(live)
                    if (liveWasMoved && rollback.exists()) {
                        check(rollback.renameTo(live)) { "فشل الاسترجاع وفشل أيضاً إعادة قاعدة البيانات السابقة" }
                    }
                    // Re-open the prior database after rollback so the app is usable.
                    AppDatabase.getInstance(context).writableDatabase
                    throw IllegalStateException(
                        "لم تُعتمد النسخة الاحتياطية؛ أُعيدت قاعدة البيانات السابقة",
                        restoreFailure
                    )
                }
            } catch (error: Exception) {
                if (liveWasMoved && rollback.exists() && !live.exists()) {
                    check(rollback.renameTo(live)) { "تعذر إعادة قاعدة البيانات السابقة بعد فشل الاستعادة" }
                }
                throw error
            } finally {
                staged.delete()
                if (rollback.exists() && live.exists()) rollback.delete()
            }
            onProgress?.invoke("اكتملت الاستعادة والتحقق من قاعدة البيانات.")
        }
    }

    /** Recovers a restore interrupted after the previous live database was moved aside. */
    fun recoverInterruptedRestore() {
        synchronized(LOCK) {
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val rollback = File(live.parentFile, "${live.name}.restore-rollback")
            if (rollback.exists()) {
                AppDatabase.getInstance(context).close()
                val liveIsValid = live.isFile && runCatching {
                    validateDatabase(live)
                    val restored = AppDatabase.getInstance(context).writableDatabase
                    validateOpenDatabase(restored)
                }.isSuccess
                if (liveIsValid) {
                    check(rollback.delete() || !rollback.exists()) {
                        "تعذر تنظيف نسخة التراجع بعد التحقق من قاعدة البيانات الحالية"
                    }
                } else {
                    AppDatabase.getInstance(context).close()
                    val rollbackIsValid = runCatching { validateDatabase(rollback) }.isSuccess
                    check(rollbackIsValid) {
                        "تعذر التعافي: قاعدة البيانات الحالية ونسخة التراجع غير سليمتين؛ احتفظ بالملفات ولا تحذفها"
                    }
                    deleteSidecars(live)
                    if (live.exists()) check(live.delete()) { "تعذر إزالة قاعدة البيانات غير السليمة" }
                    check(rollback.renameTo(live)) { "تعذر استعادة قاعدة البيانات السابقة بعد انقطاع التطبيق" }
                }
            }
            val stagePrefix = live.name.removeSuffix(".db") + ".restore-"
            live.parentFile?.listFiles()?.filter { it.name.startsWith(stagePrefix) && it.extension == "db" }
                ?.forEach { it.delete() }
        }
    }

    private fun requireBackupPermission(actorUserId: String) {
        PermissionPolicy.require(
            AppDatabase.getInstance(context).readableDatabase,
            actorUserId,
            PermissionPolicy.BACKUP_MANAGE
        )
    }

    private fun encryptSnapshot(snapshot: File, target: File, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(SECURE_RANDOM::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SECURE_RANDOM::nextBytes)
        val headerBytes = buildHeader(salt, iv)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        cipher.updateAAD(headerBytes)
        FileOutputStream(target).use { fileOut ->
            val buffered = BufferedOutputStream(fileOut)
            buffered.write(headerBytes)
            val nonClosing = object : FilterOutputStream(buffered) {
                override fun close() {
                    flush()
                }
            }
            CipherOutputStream(nonClosing, cipher).use { encryptedOut ->
                FileInputStream(snapshot).use { input -> input.copyTo(encryptedOut) }
            }
            buffered.flush()
            fileOut.fd.sync()
        }
    }

    private fun decryptToFile(source: InputStream, target: File, password: CharArray) {
        val data = DataInputStream(BufferedInputStream(source))
        val header = ByteArray(HEADER_BYTES)
        data.readFully(header)
        val parsed = parseHeader(header)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, parsed.salt, parsed.iv, parsed.iterations)
        cipher.updateAAD(header)
        try {
            FileOutputStream(target).use { fileOut ->
                val output = BufferedOutputStream(fileOut)
                CipherInputStream(data, cipher).use { decrypted ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = decrypted.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DATABASE_BYTES) { "حجم قاعدة البيانات أكبر من الحد المسموح" }
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                fileOut.fd.sync()
            }
        } catch (error: Exception) {
            target.delete()
            throw IllegalArgumentException(
                "تعذر فك تشفير النسخة. تحقق من كلمة المرور وسلامة الملف",
                error
            )
        }
        require(target.isFile && target.length() > 0L) { "ملف النسخة الاحتياطية فارغ" }
    }

    private fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(PBKDF2_ITERATIONS)
                out.write(salt)
                out.write(iv)
            }
        }.toByteArray()

    private data class ParsedHeader(val salt: ByteArray, val iv: ByteArray, val iterations: Int)

    private fun parseHeader(header: ByteArray): ParsedHeader {
        val input = DataInputStream(header.inputStream())
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "الملف ليس نسخة احتياطية معروفة من The Daily Shop" }
        require(input.readInt() == FORMAT_VERSION) { "إصدار ملف النسخة الاحتياطية غير مدعوم" }
        val iterations = input.readInt()
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "إعدادات حماية النسخة غير صالحة"
        }
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        return ParsedHeader(salt, iv, iterations)
    }

    private fun cipher(
        mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): Cipher {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = db.version
            require(version in 1..AppDatabase.DATABASE_VERSION) {
                "إصدار قاعدة البيانات داخل النسخة غير مدعوم"
            }
            validateOpenDatabase(db)
        } finally {
            db.close()
        }
    }

    private fun validateOpenDatabase(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "فشل فحص سلامة قاعدة البيانات"
            }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            require(!cursor.moveToFirst()) { "تحتوي قاعدة البيانات على علاقات غير سليمة" }
        }
        listOf("users", "products", "app_settings", "sales_invoices").forEach { table ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { cursor ->
                require(cursor.moveToFirst()) { "النسخة لا تحتوي على جدول أساسي: $table" }
            }
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "كلمة مرور النسخة الاحتياطية يجب ألا تقل عن $MIN_PASSWORD_LENGTH أحرف"
        }
    }

    private fun deleteSidecars(database: File) {
        File(database.parentFile, database.name + "-wal").delete()
        File(database.parentFile, database.name + "-shm").delete()
        File(database.parentFile, database.name + "-journal").delete()
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    companion object {
        private val LOCK = Any()
        private val SECURE_RANDOM = SecureRandom()
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x42, 0x41, 0x43, 0x4B, 0x31) // TDSBACK1
        private const val FORMAT_VERSION = 1
        private const val PBKDF2_ITERATIONS = 310_000
        private const val MIN_PBKDF2_ITERATIONS = 200_000
        private const val MAX_PBKDF2_ITERATIONS = 500_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val HEADER_BYTES = 8 + 4 + 4 + SALT_BYTES + IV_BYTES
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val MIN_PASSWORD_LENGTH = 10
        private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L
    }
}
