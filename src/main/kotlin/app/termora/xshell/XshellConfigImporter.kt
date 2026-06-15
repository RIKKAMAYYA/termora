package app.termora.xshell

import app.termora.*
import app.termora.plugin.internal.ssh.SSHProtocolProvider
import app.termora.tree.HostTreeNode
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class XshellConfigImporter(
    private val masterPassword: CharArray? = null,
) {
    data class ImportStats(
        var sessionsFound: Int = 0,
        var hostsImported: Int = 0,
        var foldersCreated: Int = 0,
        var passwordsFound: Int = 0,
        var passwordsDecrypted: Int = 0,
        var passwordsFailed: Int = 0,
        var passwordsSkipped: Int = 0,
        var passwordAuthenticationsImported: Int = 0,
        var keyAuthenticationsSkipped: Int = 0,
        var proxiesApplied: Int = 0,
        var proxiesMissing: Int = 0,
        var tunnelsImported: Int = 0,
        var jumpHostsResolved: Int = 0,
        var jumpHostsMissing: Int = 0,
    )

    data class ImportResult(
        val nodes: List<HostTreeNode>,
        val stats: ImportStats,
    )

    private data class SessionFile(
        val path: String,
        val bytes: ByteArray,
    )

    private data class ProxyProfile(
        val name: String,
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val jumpRef: String,
    )

    private data class ParsedSession(
        val sessionPath: String,
        val folderPath: String,
        val label: String,
        val host: Host,
        val proxyName: String,
        val jumpRef: String,
    )

    fun parse(file: File, folderNode: HostTreeNode): ImportResult {
        val stats = ImportStats()
        val source = readSource(file)
        stats.sessionsFound = source.sessions.size

        val parsedSessions = source.sessions.mapNotNull { parseSession(it, source.proxies, folderNode, stats) }
        val hostByRef = mutableMapOf<String, Host>()
        for (session in parsedSessions) {
            hostByRef[session.sessionPath] = session.host
            hostByRef[session.label] = session.host
        }

        val sessions = parsedSessions.map { session ->
            val jumpRef = StringUtils.firstNonBlank(session.jumpRef, source.proxies[session.proxyName]?.jumpRef).orEmpty()
            if (jumpRef.isBlank()) {
                session
            } else {
                val jumpHost = hostByRef[jumpRef]
                    ?: hostByRef[jumpRef.removeSuffix(".xsh")]
                    ?: hostByRef[jumpRef.substringAfterLast('/').removeSuffix(".xsh")]
                if (jumpHost == null) {
                    stats.jumpHostsMissing++
                    session
                } else {
                    stats.jumpHostsResolved++
                    session.copy(
                        host = session.host.copy(
                            options = session.host.options.copy(jumpHosts = listOf(jumpHost.id))
                        )
                    )
                }
            }
        }

        val nodes = buildTree(folderNode, sessions, stats)
        return ImportResult(nodes, stats)
    }

    private data class Source(
        val sessions: List<SessionFile>,
        val proxies: Map<String, ProxyProfile>,
    )

    private fun readSource(file: File): Source {
        return when {
            file.isDirectory -> readDirectory(file)
            file.extension.equals("xts", ignoreCase = true) -> readXts(file)
            file.extension.equals("xsh", ignoreCase = true) -> Source(
                sessions = listOf(SessionFile(file.name, file.readBytes())),
                proxies = emptyMap()
            )
            else -> Source(emptyList(), emptyMap())
        }
    }

    private fun readDirectory(dir: File): Source {
        val sessions = FileUtils.listFiles(dir, arrayOf("xsh"), true)
            .sortedBy { it.absolutePath }
            .map { file ->
                val relativePath = dir.toPath().relativize(file.toPath()).joinToString("/")
                SessionFile(relativePath, file.readBytes())
            }
        return Source(sessions, emptyMap())
    }

    private fun readXts(file: File): Source {
        val sessions = mutableListOf<SessionFile>()
        val proxies = mutableMapOf<String, ProxyProfile>()

        ZipFile(file, Charset.forName("GBK")).use { zip ->
            val entries = zip.entries().asSequence().toList().sortedBy { it.name }
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (name.startsWith("Xshell/", ignoreCase = true) && name.endsWith(".xsh", ignoreCase = true)) {
                    sessions.add(SessionFile(name.removePrefix("Xshell/"), bytes))
                } else if (name.startsWith("com/Common/Proxy/", ignoreCase = true)
                    && name.endsWith(".ini", ignoreCase = true)
                ) {
                    val proxy = parseProxy(name.substringAfterLast('/').removeSuffix(".ini"), bytes)
                    if (proxy != null) proxies[proxy.name] = proxy
                }
            }
        }

        return Source(sessions, proxies)
    }

    private fun parseProxy(name: String, bytes: ByteArray): ProxyProfile? {
        val ini = parseIni(decodeText(bytes))
        val section = ini["SECTION"] ?: return null
        val type = when (section["TYPE"]?.toIntOrNull()) {
            1 -> ProxyType.HTTP
            2 -> ProxyType.SOCKS5
            else -> ProxyType.No
        }
        val host = section["HOST"].orEmpty()
        val port = section["PORT"]?.toIntOrNull() ?: 0
        val password = section["PASSWORD"].orEmpty()
        return ProxyProfile(
            name = name,
            type = type,
            host = host,
            port = port,
            username = section["USERNAME"].orEmpty(),
            password = decryptPassword(password, "8.1", section["USERNAME"].orEmpty()).getOrNull().orEmpty(),
            jumpRef = StringUtils.firstNonBlank(section["SESSION"], section["JUMPHOST"]).orEmpty(),
        )
    }

    private fun parseSession(
        sessionFile: SessionFile,
        proxies: Map<String, ProxyProfile>,
        folderNode: HostTreeNode,
        stats: ImportStats,
    ): ParsedSession? {
        val ini = parseIni(decodeText(sessionFile.bytes))
        val connection = ini["CONNECTION"] ?: return null
        val protocol = connection["Protocol"] ?: SSHProtocolProvider.PROTOCOL
        if (!protocol.equals(SSHProtocolProvider.PROTOCOL, ignoreCase = true)) return null

        val normalizedPath = sessionFile.path.replace('\\', '/')
        val pathWithoutExtension = normalizedPath.removeSuffix(".xsh")
        val folderPath = pathWithoutExtension.substringBeforeLast('/', missingDelimiterValue = "")
        val label = pathWithoutExtension.substringAfterLast('/').ifBlank { connection["Host"].orEmpty() }
        val hostname = connection["Host"].orEmpty()
        if (StringUtils.isAllBlank(label, hostname)) return null

        val authentication = parseAuthentication(ini, stats)
        val proxyName = ini["CONNECTION:PROXY"]?.get("Proxy").orEmpty()
        val proxyProfile = proxies[proxyName]
        val proxy = when {
            proxyName.isBlank() -> Proxy.No
            proxyProfile == null -> {
                stats.proxiesMissing++
                Proxy.No
            }
            proxyProfile.type == ProxyType.No || proxyProfile.host.isBlank() || proxyProfile.port <= 0 -> Proxy.No
            else -> {
                stats.proxiesApplied++
                Proxy(
                    type = proxyProfile.type,
                    host = proxyProfile.host,
                    port = proxyProfile.port,
                    authenticationType = if (proxyProfile.username.isNotBlank() || proxyProfile.password.isNotBlank()) {
                        AuthenticationType.Password
                    } else {
                        AuthenticationType.No
                    },
                    username = proxyProfile.username,
                    password = proxyProfile.password,
                )
            }
        }

        val tunnelings = parseTunnelings(ini, stats)
        val options = Options.Default.copy(
            startupCommand = ini["CONNECTION:SSH"]?.get("RemoteCommand").orEmpty(),
            enableX11Forwarding = ini["CONNECTION:SSH"]?.get("ForwardX11") == "1",
        )
        val now = System.currentTimeMillis()
        val host = Host(
            id = randomUUID(),
            name = label,
            protocol = SSHProtocolProvider.PROTOCOL,
            host = hostname,
            port = connection["Port"]?.toIntOrNull() ?: 22,
            username = ini["CONNECTION:AUTHENTICATION"]?.get("UserName").orEmpty(),
            remark = StringUtils.firstNonBlank(
                connection["Description"],
                ini["SessionInfo"]?.get("Description")
            ).orEmpty(),
            authentication = authentication,
            proxy = proxy,
            options = options,
            tunnelings = tunnelings,
            parentId = folderNode.id,
            sort = now,
            createDate = now,
            updateDate = now,
        )

        stats.hostsImported++
        return ParsedSession(
            sessionPath = pathWithoutExtension,
            folderPath = folderPath,
            label = label,
            host = host,
            proxyName = proxyName,
            jumpRef = proxyProfile?.jumpRef.orEmpty(),
        )
    }

    private fun parseAuthentication(ini: Map<String, Map<String, String>>, stats: ImportStats): Authentication {
        val authentication = ini["CONNECTION:AUTHENTICATION"] ?: return Authentication.No
        val encryptedPassword = authentication["Password"].orEmpty()
        val userKey = authentication["UserKey"].orEmpty()
        if (encryptedPassword.isBlank()) {
            if (userKey.isNotBlank()) stats.keyAuthenticationsSkipped++
            return Authentication.No
        }

        stats.passwordsFound++
        val version = ini["SessionInfo"]?.get("Version").orEmpty()
        val username = authentication["UserName"].orEmpty()
        val password = decryptPassword(encryptedPassword, version, username)
        if (password.isSuccess) {
            stats.passwordsDecrypted++
        } else {
            if (masterPassword?.isNotEmpty() == true) {
                stats.passwordsFailed++
            } else {
                stats.passwordsSkipped++
            }
        }

        return if (userKey.isNotBlank()) {
            stats.keyAuthenticationsSkipped++
            Authentication.No
        } else if (password.isSuccess) {
            stats.passwordAuthenticationsImported++
            Authentication(AuthenticationType.Password, password.getOrThrow())
        } else {
            Authentication.No
        }
    }

    private fun parseTunnelings(ini: Map<String, Map<String, String>>, stats: ImportStats): List<Tunneling> {
        val ssh = ini["CONNECTION:SSH"] ?: return emptyList()
        val count = ssh["FwdReqCount"]?.toIntOrNull() ?: 0
        if (count <= 0) return emptyList()

        val tunnelings = mutableListOf<Tunneling>()
        for (index in 0 until count) {
            val prefix = "FwdReq_${index}_"
            val incoming = ssh["${prefix}Incoming"]?.toIntOrNull() ?: continue
            val localOnly = ssh["${prefix}LocalOnly"] == "1"
            val sourceHost = ssh["${prefix}Source"]
                ?.takeIf { it.isNotBlank() }
                ?: if (localOnly) "127.0.0.1" else "0.0.0.0"
            val sourcePort = ssh["${prefix}Port"]?.toIntOrNull() ?: continue
            val destinationHost = ssh["${prefix}Host"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
            val destinationPort = ssh["${prefix}HostPort"]?.toIntOrNull()?.takeIf { it > 0 } ?: sourcePort
            val type = when (incoming) {
                0 -> TunnelingType.Local
                1 -> TunnelingType.Remote
                2 -> TunnelingType.Dynamic
                else -> continue
            }
            val defaultName = when (type) {
                TunnelingType.Local -> "Local $sourceHost:$sourcePort -> $destinationHost:$destinationPort"
                TunnelingType.Remote -> "Remote $sourceHost:$sourcePort <- $destinationHost:$destinationPort"
                TunnelingType.Dynamic -> "Dynamic $sourceHost:$sourcePort"
            }
            tunnelings.add(
                Tunneling(
                    name = StringUtils.defaultIfBlank(ssh["${prefix}Description"].orEmpty(), defaultName),
                    type = type,
                    sourceHost = sourceHost,
                    sourcePort = sourcePort,
                    destinationHost = destinationHost,
                    destinationPort = destinationPort,
                )
            )
        }
        stats.tunnelsImported += tunnelings.size
        return tunnelings
    }

    private fun buildTree(
        folderNode: HostTreeNode,
        sessions: List<ParsedSession>,
        stats: ImportStats,
    ): List<HostTreeNode> {
        val nodes = folderNode.clone(setOf("Folder"))
            .childrenNode().filter { it.isFolder }
            .toMutableList()

        for (session in sessions) {
            val parent = findOrCreateFolder(folderNode, nodes, session.folderPath, stats)
            val host = session.host.copy(parentId = parent?.id ?: folderNode.id)
            val node = HostTreeNode(host)
            if (parent == null) {
                nodes.add(node)
            } else {
                parent.add(node)
            }
        }

        return nodes
    }

    private fun findOrCreateFolder(
        folderNode: HostTreeNode,
        nodes: MutableList<HostTreeNode>,
        folderPath: String,
        stats: ImportStats,
    ): HostTreeNode? {
        var parent: HostTreeNode? = null
        val names = folderPath.split('/').filter { it.isNotBlank() }
        for (name in names) {
            val siblings = if (parent == null) nodes else parent.childrenNode()
            val existing = siblings.firstOrNull { it.isFolder && it.host.name == name }
            if (existing != null) {
                parent = existing
                continue
            }

            val node = HostTreeNode(
                Host(
                    name = name,
                    protocol = "Folder",
                    parentId = parent?.id ?: folderNode.id,
                    sort = siblings.size.toLong(),
                )
            )
            stats.foldersCreated++
            if (parent == null) {
                nodes.add(node)
            } else {
                parent.add(node)
            }
            parent = node
        }
        return parent
    }

    private fun decryptPassword(encryptedPassword: String, version: String, username: String): Result<String> {
        if (encryptedPassword.isBlank()) return Result.success(StringUtils.EMPTY)
        val versionNumber = version.toDoubleOrNull() ?: 0.0
        return runCatching {
            val key = when {
                versionNumber > 0.0 && versionNumber < 5.1 -> md5("!X@s#h\$e%l^l&".toByteArray(StandardCharsets.UTF_8))
                versionNumber > 5.2 && masterPassword?.isNotEmpty() == true ->
                    sha256(String(masterPassword).toByteArray(StandardCharsets.UTF_8))
                else -> throw IllegalStateException("Unsupported Xshell password format")
            }

            val data = Base64.getDecoder().decode(encryptedPassword)
            val plaintext = if (versionNumber > 0.0 && versionNumber < 5.1) {
                rc4(key, data)
            } else {
                if (data.size <= 32) throw IllegalStateException("Invalid encrypted password")
                val ciphertext = data.copyOfRange(0, data.size - 32)
                val checksum = data.copyOfRange(data.size - 32, data.size)
                val decrypted = rc4(key, ciphertext)
                if (!sha256(decrypted).contentEquals(checksum)) {
                    throw IllegalStateException("Xshell password checksum mismatch")
                }
                decrypted
            }
            plaintext.toString(StandardCharsets.UTF_8)
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        return if (bytes.size >= 2 && (
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
                    || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
                )
        ) {
            bytes.toString(Charsets.UTF_16)
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun parseIni(text: String): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, MutableMap<String, String>>()
        var section = StringUtils.EMPTY
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1)
                result.getOrPut(section) { linkedMapOf() }
                continue
            }
            val index = line.indexOf('=')
            if (index < 0 || section.isBlank()) continue
            result.getOrPut(section) { linkedMapOf() }[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return result
    }

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
        }

        val output = ByteArray(input.size)
        var i = 0
        j = 0
        for (index in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            val k = state[(state[i] + state[j]) and 0xFF]
            output[index] = (input[index].toInt() xor k).toByte()
        }
        return output
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun md5(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(bytes)
    }

    private fun Path.joinToString(separator: String): String {
        return iterator().asSequence().joinToString(separator) { it.toString() }
    }
}
