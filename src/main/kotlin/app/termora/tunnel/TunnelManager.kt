package app.termora.tunnel

import app.termora.Application
import app.termora.ApplicationScope
import app.termora.Disposable
import app.termora.Host
import app.termora.Tunneling
import app.termora.TunnelingType
import app.termora.database.DatabaseManager
import app.termora.plugin.internal.ssh.SshClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.lang3.StringUtils
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.PortForwardingTracker
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.slf4j.LoggerFactory
import java.awt.Window
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

class TunnelManager private constructor() : Disposable {

    companion object {
        private const val AUTO_RECONNECT_KEY = "TunnelManager.AutoReconnect"
        private const val BATCH_CONNECT_TIMEOUT_SECONDS = 10L
        private const val BATCH_INITIAL_RETRIES = 2
        private val log = LoggerFactory.getLogger(TunnelManager::class.java)

        fun getInstance(): TunnelManager {
            return ApplicationScope.forApplicationScope().getOrCreate(TunnelManager::class) { TunnelManager() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = ReentrantLock()
    private val connectionCreationLocks = mutableMapOf<String, ReentrantLock>()
    private val entriesLock = Any()
    private val entries = linkedMapOf<TunnelId, TunnelEntry>()
    private val connections = mutableMapOf<String, SharedConnection>()
    private val listeners = CopyOnWriteArrayList<TunnelListener>()
    private val properties get() = DatabaseManager.getInstance().properties
    @Volatile
    private var owner: Window? = null

    private val autoReconnectIds = mutableSetOf<String>().apply {
        val text = properties.getString(AUTO_RECONNECT_KEY, "[]")
        Application.ohMyJson.runCatching {
            addAll(decodeFromString<List<String>>(text))
        }
    }

    fun getTunnels(): List<TunnelView> {
        refreshHosts()
        return synchronized(entriesLock) { entries.values.map { it.toView() } }
    }

    fun setOwner(owner: Window?) {
        this.owner = owner
    }

    fun addListener(listener: TunnelListener): Disposable {
        listeners.add(listener)
        return object : Disposable {
            override fun dispose() {
                listeners.remove(listener)
            }
        }
    }

    fun setAutoReconnect(id: TunnelId, autoReconnect: Boolean) {
        val key = id.value
        synchronized(autoReconnectIds) {
            if (autoReconnect) autoReconnectIds.add(key) else autoReconnectIds.remove(key)
            properties.putString(AUTO_RECONNECT_KEY, Application.ohMyJson.encodeToString(autoReconnectIds.toList()))
        }
        synchronized(entriesLock) {
            entries[id]?.autoReconnect = autoReconnect
        }
        notifyChanged()
    }

    fun start(id: TunnelId) {
        start(listOf(id))
    }

    fun start(ids: Collection<TunnelId>) {
        refreshHosts()
        val selected = ids.distinct()
        val entriesToStart = synchronized(entriesLock) { selected.mapNotNull { entries[it] } }
        val batch = entriesToStart.size > 1

        if (batch) {
            for (group in entriesToStart.groupBy { it.host.id }.values) {
                startBatchGroup(group)
            }
        } else {
            for (entry in entriesToStart) {
                startEntry(
                    entry = entry,
                    manual = true,
                    interactive = true,
                    initialRetries = 0,
                    fastFail = false,
                )
            }
        }
    }

    private fun startBatchGroup(entries: List<TunnelEntry>) {
        if (entries.isEmpty()) return

        val starters = entries.filter { entry ->
            synchronized(entry) {
                if (entry.state == TunnelState.Connected || entry.state == TunnelState.Connecting) {
                    false
                } else {
                    entry.stopRequested = false
                    entry.state = TunnelState.Connecting
                    entry.error = StringUtils.EMPTY
                    entry.startedAt = null
                    entry.boundAddress = StringUtils.EMPTY
                    entry.job?.cancel()
                    true
                }
            }
        }
        if (starters.isEmpty()) return

        val job = scope.launch { runBatchGroup(starters) }
        for (entry in starters) {
            synchronized(entry) {
                entry.job = job
            }
        }
        notifyChanged()
    }

    fun stop(id: TunnelId) {
        val entry = synchronized(entriesLock) { entries[id] } ?: return
        scope.launch {
            stopEntry(entry, requestedByUser = true)
        }
        notifyChanged()
    }

    fun stop(ids: Collection<TunnelId>) {
        for (id in ids) stop(id)
    }

    fun refreshHosts() {
        val hosts = app.termora.HostManager.getInstance().hosts()
            .filter { !it.isFolder && it.tunnelings.isNotEmpty() }

        val next = linkedMapOf<TunnelId, Pair<Host, Tunneling>>()
        for (host in hosts) {
            for ((index, tunneling) in host.tunnelings.withIndex()) {
                val id = TunnelId.of(host, tunneling, index)
                next[id] = host to tunneling
            }
        }

        synchronized(entriesLock) {
            val removed = entries.keys - next.keys
            for (id in removed) {
                entries.remove(id)?.let { entry ->
                    scope.launch { stopEntry(entry, requestedByUser = true) }
                }
            }

            for ((id, pair) in next) {
                val entry = entries[id]
                if (entry == null) {
                    entries[id] = TunnelEntry(
                        id = id,
                        host = pair.first,
                        tunneling = pair.second,
                        autoReconnect = synchronized(autoReconnectIds) { autoReconnectIds.contains(id.value) },
                    )
                } else {
                    entry.host = pair.first
                    entry.tunneling = pair.second
                }
            }
        }
    }

    override fun dispose() {
        val snapshot = synchronized(entriesLock) {
            entries.values.toList().also { entries.clear() }
        }
        for (entry in snapshot) {
            entry.job?.cancel()
            closeQuietly(entry)
        }
        connectionLock.withLock {
            for (connection in connections.values) {
                closeConnectionQuietly(connection)
            }
            connections.clear()
        }
        scope.cancel()
    }

    private fun startEntry(
        entry: TunnelEntry,
        manual: Boolean,
        interactive: Boolean,
        initialRetries: Int,
        fastFail: Boolean,
        notify: Boolean = true,
    ) {
        synchronized(entry) {
            if (entry.state == TunnelState.Connected || entry.state == TunnelState.Connecting) {
                return
            }
            entry.stopRequested = false
            entry.state = TunnelState.Connecting
            entry.error = StringUtils.EMPTY
            entry.startedAt = null
            entry.boundAddress = StringUtils.EMPTY

            entry.job?.cancel()
            entry.job = scope.launch { runEntry(entry, manual, interactive, initialRetries, fastFail) }
        }
        if (notify) {
            notifyChanged()
        }
    }

    private suspend fun runBatchGroup(entries: List<TunnelEntry>) {
        var initialFailures = 0
        while (scope.isActive && entries.any { !it.stopRequested }) {
            val activeEntries = entries.filter { !it.stopRequested }
            val host = withFastConnectTimeout(activeEntries.first().host)
            var connection: SharedConnection? = null

            try {
                connection = acquireConnection(host, activeEntries.first().hostDisplayName(), interactive = false)
                var attached = 0
                for (entry in activeEntries) {
                    val retained = attached > 0
                    if (retained) {
                        retainConnection(connection)
                    }
                    try {
                        attachTracker(entry, connection)
                        attached++
                    } catch (e: Exception) {
                        if (retained) {
                            releaseConnection(connection)
                        }
                        markFailed(entry, e)
                    }
                }

                if (attached == 0) {
                    releaseConnection(connection)
                    return
                }

                for (entry in activeEntries) {
                    if (entry.tracker != null) {
                        entry.job = scope.launch { monitorConnectedEntry(entry, manual = true) }
                    } else {
                        synchronized(entry) {
                            if (entry.state == TunnelState.Connecting) {
                                entry.state = TunnelState.Failed
                                entry.error = "Failed to start tunnel"
                            }
                        }
                    }
                }
                notifyChanged()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connection?.let { releaseConnection(it) }
                for (entry in activeEntries) {
                    markFailed(entry, e)
                }
            }

            if (initialFailures >= BATCH_INITIAL_RETRIES) {
                return
            }

            initialFailures++
            for (entry in activeEntries) {
                synchronized(entry) {
                    if (!entry.stopRequested) {
                        entry.state = TunnelState.Reconnecting
                    }
                }
            }
            notifyChanged()
            delay(1_000L * initialFailures)
        }
    }

    private fun stopEntry(entry: TunnelEntry, requestedByUser: Boolean) {
        synchronized(entry) {
            entry.stopRequested = requestedByUser
            entry.job?.cancel()
            entry.job = null
            closeQuietly(entry)
            entry.state = TunnelState.Stopped
            entry.error = StringUtils.EMPTY
            entry.startedAt = null
            entry.boundAddress = StringUtils.EMPTY
            notifyChanged()
        }
    }

    private suspend fun runEntry(
        entry: TunnelEntry,
        manual: Boolean,
        interactive: Boolean,
        initialRetries: Int,
        fastFail: Boolean,
    ) {
        var reconnectFailures = 0
        var initialFailures = 0
        var connectedOnce = false
        while (scope.isActive && !entry.stopRequested) {
            try {
                connect(entry, interactive, fastFail && !connectedOnce)
                reconnectFailures = 0
                initialFailures = 0
                connectedOnce = true
                monitorConnectedEntry(entry, manual)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markFailed(entry, e)
            }

            if (entry.stopRequested) {
                break
            }

            if (!connectedOnce) {
                if (initialFailures >= initialRetries) {
                    break
                }
                initialFailures++
                entry.state = TunnelState.Reconnecting
                notifyChanged()
                delay(1_000L * initialFailures)
                continue
            }

            if (!entry.autoReconnect) {
                break
            }

            reconnectFailures++
            entry.state = TunnelState.Reconnecting
            notifyChanged()
            delay(min(30_000L, 2_000L * reconnectFailures))
        }

        if (!entry.autoReconnect || entry.stopRequested || manual) {
            if (entry.tracker == null && entry.state != TunnelState.Failed) {
                entry.state = TunnelState.Stopped
                notifyChanged()
            }
        }
    }

    private suspend fun monitorConnectedEntry(entry: TunnelEntry, manual: Boolean) {
        waitUntilClosed(entry)
        if (!entry.autoReconnect || entry.stopRequested || manual) {
            if (entry.tracker == null && entry.state != TunnelState.Failed) {
                entry.state = TunnelState.Stopped
                notifyChanged()
            }
        }
    }

    private fun markFailed(entry: TunnelEntry, e: Exception) {
        closeQuietly(entry)
        entry.state = TunnelState.Failed
        entry.error = e.message ?: e.javaClass.simpleName
        entry.startedAt = null
        entry.boundAddress = StringUtils.EMPTY
        notifyChanged()

        if (log.isWarnEnabled) {
            log.warn("Tunnel [{}] failed: {}", entry.displayName(), entry.error, e)
        }
    }

    private fun connect(entry: TunnelEntry, interactive: Boolean, fastFail: Boolean) {
        val host = if (fastFail) withFastConnectTimeout(entry.host) else entry.host
        val connection = acquireConnection(host, entry.displayName(), interactive)
        try {
            attachTracker(entry, connection)
            notifyChanged()
        } catch (e: Exception) {
            releaseConnection(connection)
            throw e
        }
    }

    private fun attachTracker(entry: TunnelEntry, connection: SharedConnection) {
        val tracker = createTracker(connection.session, entry.tunneling)
        synchronized(entry) {
            entry.connection = connection
            entry.tracker = tracker
            entry.boundAddress = tracker.boundAddress?.let { "${it.hostName}:${it.port}" } ?: StringUtils.EMPTY
            entry.state = TunnelState.Connected
            entry.error = StringUtils.EMPTY
            entry.startedAt = Instant.now()
        }
    }

    private fun withFastConnectTimeout(host: Host): Host {
        val timeout = host.options.extras["timeout"]?.toLongOrNull()
        if (timeout != null && timeout <= BATCH_CONNECT_TIMEOUT_SECONDS) return host

        return host.copy(
            options = host.options.copy(
                extras = host.options.extras + ("timeout" to BATCH_CONNECT_TIMEOUT_SECONDS.toString())
            )
        )
    }

    private fun createTracker(session: ClientSession, tunneling: Tunneling): PortForwardingTracker {
        return when (tunneling.type) {
            TunnelingType.Local -> session.createLocalPortForwardingTracker(
                SshdSocketAddress(tunneling.sourceHost, tunneling.sourcePort),
                SshdSocketAddress(tunneling.destinationHost, tunneling.destinationPort)
            )

            TunnelingType.Remote -> session.createRemotePortForwardingTracker(
                SshdSocketAddress(tunneling.sourceHost, tunneling.sourcePort),
                SshdSocketAddress(tunneling.destinationHost, tunneling.destinationPort)
            )

            TunnelingType.Dynamic -> session.createDynamicPortForwardingTracker(
                SshdSocketAddress(tunneling.sourceHost, tunneling.sourcePort)
            )
        }
    }

    private suspend fun waitUntilClosed(entry: TunnelEntry) {
        while (scope.isActive && !entry.stopRequested) {
            val tracker = entry.tracker
            val session = entry.connection?.session
            if (tracker == null || session == null || !tracker.isOpen || !session.isOpen) {
                break
            }
            delay(1_000)
        }
        closeQuietly(entry)
    }

    private fun closeQuietly(entry: TunnelEntry) {
        runCatching { entry.tracker?.close() }
        entry.connection?.let { releaseConnection(it) }
        entry.tracker = null
        entry.connection = null
    }

    private fun acquireConnection(host: Host, displayName: String, interactive: Boolean): SharedConnection {
        val key = host.id
        val creationLock = connectionLock.withLock {
            connectionCreationLocks.getOrPut(key) { ReentrantLock() }
        }

        return creationLock.withLock {
            val reusable = connectionLock.withLock {
                connections[key]?.let { connection ->
                    if (connection.session.isOpen && !connection.client.isClosed && !connection.client.isClosing) {
                        connection.refCount++
                        return@withLock connection
                    }
                    connections.remove(key)
                    closeConnectionQuietly(connection)
                }
                null
            }
            if (reusable != null) return@withLock reusable

            val client = owner
                ?.takeIf { interactive }
                ?.let { SshClients.openClient(host, it, displayName) }
                ?: SshClients.openClient(host)
            val session = try {
                SshClients.openSession(host, client)
            } catch (e: Exception) {
                client.close(true).await()
                throw e
            }

            SharedConnection(key, client, session).also { connection ->
                connection.refCount = 1
                connectionLock.withLock {
                    connections[key] = connection
                }
            }
        }
    }

    private fun releaseConnection(connection: SharedConnection) {
        connectionLock.withLock {
            connection.refCount--
            if (connection.refCount > 0) return

            val current = connections[connection.key]
            if (current === connection) {
                connections.remove(connection.key)
            }
            closeConnectionQuietly(connection)
        }
    }

    private fun retainConnection(connection: SharedConnection) {
        connectionLock.withLock {
            connection.refCount++
        }
    }

    private fun closeConnectionQuietly(connection: SharedConnection) {
        runCatching { connection.session.close(true).await() }
        runCatching { connection.client.close(true).await() }
    }

    private fun notifyChanged() {
        val snapshot = synchronized(entriesLock) { entries.values.map { it.toView() } }
        for (listener in listeners) {
            listener.tunnelsChanged(snapshot)
        }
    }

    private class TunnelEntry(
        val id: TunnelId,
        var host: Host,
        var tunneling: Tunneling,
        var autoReconnect: Boolean,
    ) {
        var state: TunnelState = TunnelState.Stopped
        var error: String = StringUtils.EMPTY
        var boundAddress: String = StringUtils.EMPTY
        var startedAt: Instant? = null
        var stopRequested: Boolean = false
        var job: Job? = null
        var connection: SharedConnection? = null
        var tracker: PortForwardingTracker? = null

        fun displayName(): String {
            return if (tunneling.name.isBlank()) host.name else "${host.name} / ${tunneling.name}"
        }

        fun hostDisplayName(): String {
            return "${host.name} (${host.username}@${host.host})"
        }

        fun toView(): TunnelView {
            return TunnelView(
                id = id,
                host = host,
                tunneling = tunneling,
                state = state,
                autoReconnect = autoReconnect,
                boundAddress = boundAddress,
                error = error,
                startedAt = startedAt,
            )
        }
    }

    private class SharedConnection(
        val key: String,
        val client: SshClient,
        val session: ClientSession,
        var refCount: Int = 0,
    )
}

fun interface TunnelListener {
    fun tunnelsChanged(tunnels: List<TunnelView>)
}

@JvmInline
value class TunnelId(val value: String) {
    companion object {
        fun of(host: Host, tunneling: Tunneling, index: Int): TunnelId {
            return TunnelId(
                listOf(
                    host.id,
                    index.toString(),
                    tunneling.type.name,
                    tunneling.sourceHost,
                    tunneling.sourcePort.toString(),
                    tunneling.destinationHost,
                    tunneling.destinationPort.toString(),
                    tunneling.name,
                ).joinToString("|")
            )
        }
    }
}

data class TunnelView(
    val id: TunnelId,
    val host: Host,
    val tunneling: Tunneling,
    val state: TunnelState,
    val autoReconnect: Boolean,
    val boundAddress: String,
    val error: String,
    val startedAt: Instant?,
) {
    val sourceText: String get() = "${tunneling.sourceHost}:${tunneling.sourcePort}"
    val destinationText: String
        get() = if (tunneling.type == TunnelingType.Dynamic) {
            "SOCKS"
        } else {
            "${tunneling.destinationHost}:${tunneling.destinationPort}"
        }

    fun matches(pattern: String): Boolean {
        if (pattern.isBlank()) return true
        val p = pattern.trim()
        return host.name.contains(p, true) ||
                host.host.contains(p, true) ||
                tunneling.name.contains(p, true) ||
                tunneling.type.name.contains(p, true) ||
                sourceText.contains(p, true) ||
                destinationText.contains(p, true) ||
                state.name.contains(p, true)
    }
}

enum class TunnelState {
    Stopped,
    Connecting,
    Connected,
    Reconnecting,
    Failed
}
