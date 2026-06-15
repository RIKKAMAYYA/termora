package app.termora.xshell

import app.termora.Host
import app.termora.database.OwnerType
import app.termora.tree.HostTreeNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XshellConfigImporterTest {

    @Test
    fun shouldImportXtsBackupWithPasswordsFoldersAndTunnels() {
        val file = File("/Users/renxiang/Downloads/xbackup20260613.xts")
        if (!file.isFile) return

        val root = HostTreeNode(
            Host(
                id = "0",
                name = "root",
                protocol = "Folder",
                ownerType = OwnerType.User.name,
            )
        )

        val result = XshellConfigImporter("123456".toCharArray()).parse(file, root)
        val hosts = result.nodes.flatMap { listOf(it) + it.getAllChildren() }
            .map { it.host }
            .filter { !it.isFolder }

        assertEquals(235, result.stats.sessionsFound)
        assertEquals(235, result.stats.hostsImported)
        assertEquals(223, result.stats.passwordsFound)
        assertEquals(190, result.stats.passwordsDecrypted)
        assertEquals(33, result.stats.passwordsFailed)
        assertEquals(8, result.stats.passwordAuthenticationsImported)
        assertEquals(218, result.stats.keyAuthenticationsSkipped)
        assertEquals(29, result.stats.tunnelsImported)
        assertEquals(235, hosts.size)
        assertTrue(result.nodes.any { it.isFolder })
        assertEquals(8, hosts.count { it.authentication.password.isNotBlank() })
        assertTrue(hosts.any { it.tunnelings.isNotEmpty() })
    }
}
