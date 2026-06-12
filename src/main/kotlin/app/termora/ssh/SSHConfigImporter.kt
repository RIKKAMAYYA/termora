package app.termora.ssh

import app.termora.*
import app.termora.account.AccountManager
import app.termora.account.AccountOwner
import app.termora.database.DatabaseChangedExtension
import app.termora.database.OwnerType
import app.termora.keymgr.KeyManager
import app.termora.keymgr.OhKeyPair
import app.termora.plugin.internal.ssh.SSHProtocolProvider
import app.termora.tree.HostTreeNode
import org.apache.sshd.client.config.hosts.HostConfigEntry
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.config.keys.FilePasswordProvider.ResourceDecodeResult
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.util.*
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Enhanced SSH config importer that handles IdentityFile, ProxyJump, LocalForward, and RemoteForward.
 */
class SSHConfigImporter private constructor(
    private val owner: AccountOwner,
) {
    companion object {
        private val log = LoggerFactory.getLogger(SSHConfigImporter::class.java)

        fun create(): SSHConfigImporter {
            val accountManager = AccountManager.getInstance()
            return SSHConfigImporter(
                AccountOwner(accountManager.getAccountId(), accountManager.getEmail(), OwnerType.User)
            )
        }
    }

    data class ImportStats(
        var hostsCreated: Int = 0,
        var hostsUpdated: Int = 0,
        var keysImported: Int = 0,
        var keysReused: Int = 0,
        var keysSkippedInvalid: Int = 0,
        var missingKeyRefs: Int = 0,
        var missingJumpRefs: Int = 0,
    )

    /**
     * Parse SSH config and return HostTreeNode list ready for import into the tree.
     */
    fun parseSSHConfig(folder: HostTreeNode): SSHImportResult {
        val entries = try {
            HostConfigEntry.readHostConfigEntries(HostConfigEntry.getDefaultHostConfigFile())
        } catch (e: Exception) {
            log.error("Failed to read SSH config", e)
            return SSHImportResult(nodes = emptyList(), stats = ImportStats())
        }

        if (entries.isEmpty()) {
            return SSHImportResult(nodes = emptyList(), stats = ImportStats())
        }

        // Deduplicate entries (same host may appear from multiple match patterns after collation)
        val uniqueEntries = deduplicateEntries(entries)

        // Gather existing data
        val keyManager = KeyManager.getInstance()
        val hostManager = HostManager.getInstance()
        val existingHosts = hostManager.hosts()
        val hostByName = mutableMapOf<String, Host>()
        var maxSort = 0L
        for (h in existingHosts) {
            hostByName[h.name] = h
            if (h.sort > maxSort) maxSort = h.sort
        }

        val existingKeys = keyManager.getOhKeyPairs()
        val existingByMaterial = mutableMapOf<String, OhKeyPair>()
        val existingByName = mutableMapOf<String, OhKeyPair>()
        for (k in existingKeys) {
            existingByMaterial["${k.publicKey}\n${k.privateKey}"] = k
            existingByName[k.name] = k
        }

        val stats = ImportStats()

        // Phase 1: Import keys from all identity files
        val keyIdByPath = importKeys(uniqueEntries, keyManager, existingByMaterial, existingByName, stats)

        // Phase 2: Build Host objects
        val now = System.currentTimeMillis()
        val toSave = mutableListOf<Host>()
        val newHostByName = mutableMapOf<String, Host>()

        // Pre-create all hosts so jump host references can be resolved
        val hostBuilders = mutableListOf<HostBuilder>()
        for (entry in uniqueEntries) {
            val hostName = entry.hostName ?: ""
            if (hostName.isBlank()) continue

            val name = if (entry.host.isNotBlank()) entry.host else hostName
            val port = if (entry.port > 0) entry.port else 22
            val username = entry.username ?: ""

            // Resolve identity key
            var keyId = ""
            val identities = entry.identities
            if (identities.isNotEmpty()) {
                for (identityPath in identities) {
                    val resolved = resolveIdentityPath(identityPath, entry)
                    if (resolved != null && keyIdByPath.containsKey(resolved.toString())) {
                        keyId = keyIdByPath[resolved.toString()] ?: ""
                        break
                    }
                }
                if (keyId.isBlank() && identities.isNotEmpty()) {
                    stats.missingKeyRefs++
                }
            }

            // Authentication
            val auth = if (keyId.isNotBlank()) {
                Authentication(AuthenticationType.PublicKey, keyId)
            } else {
                Authentication.No
            }

            // ProxyJump
            val proxyJump = entry.proxyJump ?: ""

            // Tunnels
            val tunnels = parseTunnels(entry)

            // Check if this host already exists
            val existing = hostByName[name] ?: newHostByName[name]
            val hostId = existing?.id ?: randomUUID()

            val host = Host(
                id = hostId,
                name = name,
                protocol = SSHProtocolProvider.PROTOCOL,
                host = hostName,
                port = port,
                username = username,
                authentication = auth,
                proxy = Proxy.No,
                options = Options.Default,
                tunnelings = tunnels,
                sort = existing?.sort ?: ++maxSort,
                parentId = existing?.parentId ?: folder.host.id,
                ownerId = existing?.ownerId ?: owner.id,
                ownerType = existing?.ownerType ?: owner.type.name,
                creatorId = existing?.creatorId ?: owner.id,
                createDate = existing?.createDate ?: now,
                updateDate = now,
            )

            hostBuilders.add(HostBuilder(host, proxyJump, existing != null))
            newHostByName[name] = host

            // Also register by aliases
            val aliases = HostConfigEntry.parseConfigValue(entry.host)
            for (alias in aliases) {
                newHostByName.putIfAbsent(alias, host)
            }
        }

        // Phase 3: Resolve jump hosts
        for (builder in hostBuilders) {
            val host = builder.host
            val proxyJump = builder.proxyJump

            if (proxyJump.isNotBlank()) {
                val jumpNames = proxyJump.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val jumpIds = mutableListOf<String>()
                for (jumpName in jumpNames) {
                    val jumpHost = hostByName[jumpName] ?: newHostByName[jumpName]
                    if (jumpHost != null) {
                        jumpIds.add(jumpHost.id)
                    } else {
                        stats.missingJumpRefs++
                    }
                }
                if (jumpIds.isNotEmpty()) {
                    builder.host = host.copy(
                        options = host.options.copy(jumpHosts = jumpIds)
                    )
                }
            }

            toSave.add(builder.host)
            if (builder.isUpdate) stats.hostsUpdated++ else stats.hostsCreated++
        }

        // Phase 4: Save hosts to database
        // We save directly here so that HostManager.addHost handles encryption
        // (the caller's addHost loop in doImportHosts would also work, but we need
        // preprocessing done first)
        for (host in toSave) {
            hostManager.addHost(
                host.copy(ownerType = folder.host.ownerType, ownerId = folder.host.ownerId),
                DatabaseChangedExtension.Source.User
            )
        }

        // Phase 5: Build tree nodes
        val nodes = mutableListOf<HostTreeNode>()
        for (host in toSave) {
            if (host.parentId == folder.host.id || host.parentId == "0") {
                nodes.add(HostTreeNode(host))
            }
        }

        // Reload hosts so the caller sees the updated list
        // (the existing doImportHosts code will reload the tree)

        return SSHImportResult(nodes = nodes, stats = stats)
    }

    data class HostBuilder(
        var host: Host,
        val proxyJump: String,
        val isUpdate: Boolean,
    )

    data class SSHImportResult(
        val nodes: List<HostTreeNode>,
        val stats: ImportStats,
    )

    /**
     * Deduplicate HostConfigEntry list — same effective host may appear due to pattern matching.
     */
    private fun deduplicateEntries(entries: List<HostConfigEntry>): List<HostConfigEntry> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<HostConfigEntry>()
        for (entry in entries) {
            val key = "${entry.hostName}|${entry.port}|${entry.username}"
            if (seen.add(key)) {
                result.add(entry)
            }
        }
        return result
    }

    /**
     * Discover and import all identity keys referenced in SSH config entries.
     */
    private fun importKeys(
        entries: List<HostConfigEntry>,
        keyManager: KeyManager,
        existingByMaterial: MutableMap<String, OhKeyPair>,
        existingByName: MutableMap<String, OhKeyPair>,
        stats: ImportStats,
    ): Map<String, String> {
        val keyIdByPath = mutableMapOf<String, String>()
        val seenPaths = LinkedHashSet<Path>()

        // Collect all identity file paths
        for (entry in entries) {
            for (identityPath in entry.identities) {
                val resolved = resolveIdentityPath(identityPath, entry) ?: continue
                seenPaths.add(resolved)
            }
        }

        for (path in seenPaths) {
            if (!path.isRegularFile()) continue

            val loaded = try {
                loadKey(path)
            } catch (e: Exception) {
                log.warn("Skipping invalid key file: $path — ${e.message}")
                stats.keysSkippedInvalid++
                continue
            }

            if (loaded == null) {
                stats.keysSkippedInvalid++
                continue
            }

            val material = "${loaded.publicKey}\n${loaded.privateKey}"
            var existing = existingByMaterial[material]
            if (existing == null) existing = existingByName[loaded.name]

            if (existing != null) {
                keyIdByPath[path.toString()] = existing.id
                stats.keysReused++
            } else {
                val id = randomUUID()
                val keyPair = OhKeyPair(
                    id = id,
                    publicKey = loaded.publicKey,
                    privateKey = loaded.privateKey,
                    type = loaded.type,
                    name = loaded.name,
                    remark = "",
                    length = loaded.length,
                    sort = System.currentTimeMillis(),
                    updateDate = System.currentTimeMillis(),
                )
                keyManager.addOhKeyPair(keyPair, owner)
                existingByMaterial[material] = keyPair
                existingByName[loaded.name] = keyPair
                keyIdByPath[path.toString()] = id
                stats.keysImported++
            }
        }

        return keyIdByPath
    }

    data class LoadedKey(
        val path: String,
        val name: String,
        val publicKey: String,
        val privateKey: String,
        val type: String,
        val length: Int,
    )

    /**
     * Load a private key file using Apache SSHD's key loading facilities.
     */
    private fun loadKey(path: Path): LoadedKey? {
        return try {
            Files.newInputStream(path).use { inputStream ->
                val pairs = SecurityUtils.loadKeyPairIdentities(
                    null,
                    NamedResource.ofName(path.toString()),
                    inputStream,
                    object : FilePasswordProvider {
                        override fun getPassword(
                            session: org.apache.sshd.common.session.SessionContext?,
                            resource: NamedResource,
                            index: Int
                        ): String {
                            return "" // empty password for unencrypted keys
                        }

                        override fun handleDecodeAttemptResult(
                            session: org.apache.sshd.common.session.SessionContext?,
                            resource: NamedResource,
                            index: Int,
                            password: String,
                            err: Exception
                        ): ResourceDecodeResult {
                            // Skip encrypted keys
                            return ResourceDecodeResult.IGNORE
                        }
                    }
                )
                val iterator = pairs.iterator()
                if (!iterator.hasNext()) return null
                val keyPair = iterator.next()

                LoadedKey(
                    path = path.toString(),
                    name = path.name,
                    publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                    privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded),
                    type = keyPair.public.algorithm.uppercase(),
                    length = KeyUtils.getKeySize(keyPair.public),
                )
            }
        } catch (e: Exception) {
            log.debug("Cannot load key from $path: ${e.message}")
            null
        }
    }

    /**
     * Resolve an identity file path, handling ~ and %d/%u tokens.
     */
    private fun resolveIdentityPath(identityPath: String, entry: HostConfigEntry): Path? {
        return try {
            val resolved = HostConfigEntry.resolveIdentityFilePath(
                identityPath,
                entry.hostName ?: "",
                entry.port,
                entry.username ?: ""
            )
            Path.of(resolved).normalize()
        } catch (e: Exception) {
            log.debug("Cannot resolve identity path: $identityPath — ${e.message}")
            null
        }
    }

    /**
     * Parse LocalForward and RemoteForward entries from HostConfigEntry properties.
     */
    private fun parseTunnels(entry: HostConfigEntry): List<Tunneling> {
        val tunnels = mutableListOf<Tunneling>()

        for ((key, value) in entry.properties) {
            when (key.lowercase()) {
                "localforward" -> {
                    val tunnel = parseLocalForward(value)
                    if (tunnel != null) tunnels.add(tunnel)
                }
                "remoteforward" -> {
                    val tunnel = parseRemoteForward(value)
                    if (tunnel != null) tunnels.add(tunnel)
                }
            }
        }

        return tunnels
    }

    /**
     * Parse a LocalForward value like: "bind_host:bind_port remote_host:remote_port"
     * or: "bind_port remote_host:remote_port"
     */
    private fun parseLocalForward(value: String): Tunneling? {
        val parts = value.trim().split("\\s+".toRegex(), limit = 2)
        if (parts.isEmpty()) return null

        val bindPart = parts[0]
        val bindHost: String
        val bindPort: Int

        val bindSegments = bindPart.split(":")
        if (bindSegments.size == 1) {
            bindHost = "127.0.0.1"
            bindPort = bindSegments[0].toIntOrNull() ?: return null
        } else {
            bindHost = if (bindSegments[0].isBlank()) "127.0.0.1" else bindSegments[0]
            bindPort = bindSegments.last().toIntOrNull() ?: return null
        }

        val (destHost, destPort) = if (parts.size > 1) {
            val dest = parts[1]
            val colonIdx = dest.lastIndexOf(':')
            if (colonIdx > 0 && colonIdx < dest.length - 1) {
                val host = dest.substring(0, colonIdx)
                val port = dest.substring(colonIdx + 1).toIntOrNull() ?: 3306
                Pair(host, port)
            } else {
                Pair(dest, 3306)
            }
        } else {
            Pair("127.0.0.1", 3306)
        }

        val label = "Local $bindHost:$bindPort -> $destHost:$destPort"
        return Tunneling(
            name = label,
            type = TunnelingType.Local,
            sourceHost = bindHost,
            sourcePort = bindPort,
            destinationHost = destHost,
            destinationPort = destPort,
        )
    }

    /**
     * Parse a RemoteForward value like: "remote_port remote_host:local_port"
     */
    private fun parseRemoteForward(value: String): Tunneling? {
        val parts = value.trim().split("\\s+".toRegex(), limit = 2)
        if (parts.isEmpty()) return null

        val bindPart = parts[0]
        val bindHost: String
        val bindPort: Int

        val bindSegments = bindPart.split(":")
        if (bindSegments.size == 1) {
            bindHost = "127.0.0.1"
            bindPort = bindSegments[0].toIntOrNull() ?: return null
        } else {
            bindHost = if (bindSegments[0].isBlank()) "127.0.0.1" else bindSegments[0]
            bindPort = bindSegments.last().toIntOrNull() ?: return null
        }

        val (destHost, destPort) = if (parts.size > 1) {
            val dest = parts[1]
            val colonIdx = dest.lastIndexOf(':')
            if (colonIdx > 0 && colonIdx < dest.length - 1) {
                val host = dest.substring(0, colonIdx)
                val port = dest.substring(colonIdx + 1).toIntOrNull() ?: 22
                Pair(host, port)
            } else {
                Pair(dest, 22)
            }
        } else {
            Pair("127.0.0.1", 22)
        }

        val label = "Remote $bindHost:$bindPort <- $destHost:$destPort"
        return Tunneling(
            name = label,
            type = TunnelingType.Remote,
            sourceHost = bindHost,
            sourcePort = bindPort,
            destinationHost = destHost,
            destinationPort = destPort,
        )
    }
}
