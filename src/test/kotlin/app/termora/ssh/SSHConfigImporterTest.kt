package app.termora.ssh

import app.termora.account.AccountOwner
import app.termora.database.OwnerType
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class SSHConfigImporterTest {

    @Test
    fun shouldLoadUnencryptedIdentityFile() {
        val keyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 1024)
        val keyFile = Files.createTempFile("termora-ssh-config-import-", "")

        try {
            Files.newOutputStream(keyFile).use {
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, null, null, it)
            }

            val loaded = loadKey(keyFile)

            assertNotNull(loaded)
            assertEquals("RSA", loaded.type)
            assertEquals(keyFile.fileName.toString(), loaded.name)
        } finally {
            Files.deleteIfExists(keyFile)
        }
    }

    @Test
    fun shouldCreateUniqueKeyNamesForSameFileName() {
        val importer = newImporter()
        val dir = Files.createTempDirectory("termora-ssh-config-import-")
        val first = Files.createDirectories(dir.resolve("first")).resolve("id_rsa")
        val second = Files.createDirectories(dir.resolve("second")).resolve("id_rsa")

        try {
            val firstName = createUniqueKeyName(importer, first, first.fileName.toString(), emptyMap())
            val existingNames = mapOf(firstName to emptyKeyPair(firstName))

            val secondName = createUniqueKeyName(importer, second, second.fileName.toString(), existingNames)

            assertEquals("first/id_rsa", firstName)
            assertEquals("second/id_rsa", secondName)
            assertNotEquals(firstName, secondName)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun loadKey(path: Path): SSHConfigImporter.LoadedKey? {
        val importer = newImporter()
        val method = SSHConfigImporter::class.java.getDeclaredMethod("loadKey", Path::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(importer, path) as SSHConfigImporter.LoadedKey?
    }

    private fun createUniqueKeyName(
        importer: SSHConfigImporter,
        path: Path,
        fallbackName: String,
        existingByName: Map<String, app.termora.keymgr.OhKeyPair>,
    ): String {
        val method = SSHConfigImporter::class.java.getDeclaredMethod(
            "createUniqueKeyName",
            Path::class.java,
            String::class.java,
            Map::class.java,
        )
        method.isAccessible = true
        return method.invoke(importer, path, fallbackName, existingByName) as String
    }

    private fun newImporter(): SSHConfigImporter {
        val constructor = SSHConfigImporter::class.java.getDeclaredConstructor(AccountOwner::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(AccountOwner("test", "test", OwnerType.User))
    }

    private fun emptyKeyPair(name: String): app.termora.keymgr.OhKeyPair {
        return app.termora.keymgr.OhKeyPair.empty.copy(id = name, name = name)
    }
}
