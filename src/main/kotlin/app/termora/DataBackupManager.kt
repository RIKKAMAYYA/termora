package app.termora

import app.termora.database.Data
import app.termora.database.DataType
import app.termora.database.DatabaseChangedExtension
import app.termora.Application.ohMyJson
import app.termora.database.DatabaseManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Encrypts and decrypts full Termora data backups.
 */
object DataBackupManager {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
    private const val SALT_LENGTH = 16
    private const val GCM_IV_LENGTH = 12
    private const val BACKUP_VERSION = 1

    private val backupJson = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Serializable
    data class BackupContainer(
        val version: Int,
        val exportDate: Long,
        val data: Map<String, List<String>>,
    )

    data class ImportStats(
        val created: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
    )

    data class ImportResult(
        val statsByType: Map<String, ImportStats> = emptyMap(),
        val errors: List<String> = emptyList(),
    ) {
        val totalCreated get() = statsByType.values.sumOf { it.created }
        val totalUpdated get() = statsByType.values.sumOf { it.updated }
        val totalSkipped get() = statsByType.values.sumOf { it.skipped }
    }

    /**
     * Export all data as encrypted bytes.
     */
    fun exportAllData(password: CharArray): ByteArray {
        val db = DatabaseManager.getInstance()
        val dataByType = mutableMapOf<String, MutableList<String>>()

        for (type in DataType.entries) {
            val raw = db.rawData(type)
            if (raw.isNotEmpty()) {
                dataByType[type.name] = raw.map { it.data }.toMutableList()
            }
        }

        val container = BackupContainer(
            version = BACKUP_VERSION,
            exportDate = System.currentTimeMillis(),
            data = dataByType,
        )

        val jsonBytes = backupJson.encodeToString(container).toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_LENGTH)
        SecureRandom.getInstanceStrong().nextBytes(salt)

        val key = PBKDF2.hash(salt, password, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom.getInstanceStrong().nextBytes(iv)

        val encrypted = AES.GCM.encrypt(key, iv, jsonBytes)

        // Format: salt (16) + iv (12) + ciphertext
        val result = ByteArray(salt.size + iv.size + encrypted.size)
        System.arraycopy(salt, 0, result, 0, salt.size)
        System.arraycopy(iv, 0, result, salt.size, iv.size)
        System.arraycopy(encrypted, 0, result, salt.size + iv.size, encrypted.size)

        return result
    }

    /**
     * Import data from encrypted bytes. Returns statistics about what was imported.
     */
    fun importAllData(password: CharArray, encryptedBytes: ByteArray): ImportResult {
        val salt = encryptedBytes.copyOfRange(0, SALT_LENGTH)
        val iv = encryptedBytes.copyOfRange(SALT_LENGTH, SALT_LENGTH + GCM_IV_LENGTH)
        val ciphertext = encryptedBytes.copyOfRange(SALT_LENGTH + GCM_IV_LENGTH, encryptedBytes.size)

        val key = PBKDF2.hash(salt, password, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)

        val jsonBytes: ByteArray
        try {
            jsonBytes = AES.GCM.decrypt(key, iv, ciphertext)
        } catch (e: AEADBadTagException) {
            return ImportResult(errors = listOf("Bad password or corrupted file"))
        } catch (e: Exception) {
            return ImportResult(errors = listOf("Decryption failed: ${e.message}"))
        }

        val container: BackupContainer
        try {
            container = backupJson.decodeFromString<BackupContainer>(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return ImportResult(errors = listOf("Invalid backup file format: ${e.message}"))
        }

        val db = DatabaseManager.getInstance()
        val statsByType = mutableMapOf<String, ImportStats>()

        for ((typeName, dataList) in container.data) {
            val type = try {
                DataType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                continue
            }

            var created = 0
            var updated = 0
            var skipped = 0

            for (jsonData in dataList) {
                try {
                    val imported = ohMyJson.decodeFromString<Data>(jsonData)
                    val existing = db.data(imported.id)

                    if (existing != null && existing.type != type.name) {
                        skipped++
                        continue
                    }

                    db.saveAndIncrementVersion(imported, DatabaseChangedExtension.Source.User)

                    if (existing == null) {
                        created++
                    } else {
                        updated++
                    }
                } catch (e: Exception) {
                    skipped++
                }
            }

            statsByType[typeName] = ImportStats(created, updated, skipped)
        }

        return ImportResult(statsByType = statsByType)
    }
}
