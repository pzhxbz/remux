package dev.remux.app.network

import android.annotation.SuppressLint

import dev.remux.app.BuildConfig
import dev.remux.app.data.RelayProfile
import dev.remux.app.protocol.AgentPayload
import dev.remux.app.protocol.ClientPayload
import dev.remux.app.protocol.Command
import dev.remux.app.protocol.CommandResult
import dev.remux.app.protocol.MAX_TERMINAL_CHUNK_BYTES
import dev.remux.app.protocol.MachineInfo
import dev.remux.app.protocol.PROTOCOL_VERSION
import dev.remux.app.protocol.ProtocolCodec
import dev.remux.app.protocol.RemuxCrypto
import dev.remux.app.protocol.SizePolicy
import dev.remux.app.protocol.WireMessage
import java.io.Closeable
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionPhase {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class RelayStatus(
    val phase: ConnectionPhase = ConnectionPhase.STOPPED,
    val message: String? = null,
    val retryInSeconds: Long? = null,
)

enum class TerminalPhase {
    OPEN,
    CONNECTION_LOST,
    CLOSED,
    OVERFLOW,
}

data class TerminalStatus(
    val phase: TerminalPhase = TerminalPhase.OPEN,
    val message: String? = null,
    val exitCode: UInt? = null,
)

class RelayException(message: String, cause: Throwable? = null) : IOException(message, cause)

class AgentException(val code: String, message: String) : IOException(message)

class TerminalHandle internal constructor(
    val machineId: String,
    val sessionId: String,
    val streamId: String,
    val ignoreSize: Boolean,
    private val relayClient: RelayClient,
) : Closeable {
    private val outputChannel = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val mutableStatus = MutableStateFlow(TerminalStatus())
    private val closed = AtomicBoolean(false)

    val output = outputChannel.receiveAsFlow()
    val status: StateFlow<TerminalStatus> = mutableStatus.asStateFlow()

    suspend fun send(bytes: ByteArray) {
        check(bytes.isNotEmpty()) { "terminal input cannot be empty" }
        relayClient.sendTerminalInput(machineId, streamId, bytes)
    }

    suspend fun resize(cols: Int, rows: Int) {
        require(cols in 1..UShort.MAX_VALUE.toInt()) { "terminal cols are out of range" }
        require(rows in 1..UShort.MAX_VALUE.toInt()) { "terminal rows are out of range" }
        relayClient.resizeTerminal(machineId, streamId, cols.toUShort(), rows.toUShort())
    }

    suspend fun refresh() {
        relayClient.refreshTerminal(machineId, streamId)
    }

    suspend fun selectWindow(windowId: String) {
        relayClient.selectTerminalWindow(machineId, streamId, windowId)
    }

    suspend fun detach() {
        if (closed.compareAndSet(false, true)) {
            try {
                relayClient.detachTerminal(machineId, streamId)
            } finally {
                outputChannel.close()
                mutableStatus.value = TerminalStatus(TerminalPhase.CLOSED, "Detached")
            }
        }
    }

    override fun close() {
        relayClient.scope.launch { detach() }
    }

    internal fun deliver(bytes: ByteArray): Boolean = outputChannel.trySend(bytes).isSuccess

    internal fun markConnectionLost(message: String) {
        mutableStatus.value = TerminalStatus(TerminalPhase.CONNECTION_LOST, message)
    }

    internal fun markOverflow() {
        if (closed.compareAndSet(false, true)) {
            mutableStatus.value = TerminalStatus(
                TerminalPhase.OVERFLOW,
                "Terminal output exceeded the local 2 MiB safety queue; reattach for a clean redraw",
            )
            outputChannel.close(RelayException("terminal output queue overflow"))
        }
    }

    internal fun markClosed(reason: String, exitCode: UInt?) {
        if (closed.compareAndSet(false, true)) {
            mutableStatus.value = TerminalStatus(TerminalPhase.CLOSED, reason, exitCode)
            outputChannel.close()
        }
    }
}

class RelayClient(
    private val profile: RelayProfile,
    private val clientId: String,
    private val clientName: String,
    internal val scope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : Closeable {
    private data class PendingRequest(
        val machineId: String,
        val command: Command,
        val result: CompletableDeferred<CommandResult>,
    )

    private val lock = Any()
    private val mutableStatus = MutableStateFlow(RelayStatus())
    private val mutableMachines = MutableStateFlow<Map<String, MachineInfo>>(emptyMap())
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val terminals = ConcurrentHashMap<String, TerminalHandle>()
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var retrySeconds = 1L
    private var desired = false

    val status: StateFlow<RelayStatus> = mutableStatus.asStateFlow()
    val machines: StateFlow<Map<String, MachineInfo>> = mutableMachines.asStateFlow()

    init {
        require(UUID.fromString(clientId).toString() == clientId) { "clientId must be a normalized UUID" }
        require(profile.clientToken.length >= 16) { "relay client token must be at least 16 characters" }
        validateRelayUrl(profile.relayUrl)
    }

    fun start() {
        synchronized(lock) {
            desired = true
            if (socket != null) return
            openSocket(isReconnect = mutableStatus.value.phase != ConnectionPhase.STOPPED)
        }
    }

    suspend fun awaitConnected(timeoutMillis: Long = 15_000) {
        start()
        withTimeout(timeoutMillis) {
            while (true) {
                val current = status.value
                when (current.phase) {
                    ConnectionPhase.CONNECTED -> return@withTimeout
                    ConnectionPhase.ERROR -> throw RelayException(current.message ?: "relay connection failed")
                    else -> delay(25)
                }
            }
        }
    }

    suspend fun request(machineId: String, command: Command): CommandResult {
        awaitConnected()
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandResult>()
        val pending = PendingRequest(machineId, command, deferred)
        pendingRequests[requestId] = pending
        try {
            sendPayload(
                machineId,
                ClientPayload.Request(requestId = requestId, command = command),
            )
            return withTimeout(15_000) { deferred.await() }
        } finally {
            pendingRequests.remove(requestId, pending)
        }
    }

    suspend fun openTerminal(
        machineId: String,
        sessionId: String,
        cols: Int,
        rows: Int,
        sizePolicy: SizePolicy = SizePolicy.AUTO,
    ): TerminalHandle {
        require(cols in 1..UShort.MAX_VALUE.toInt())
        require(rows in 1..UShort.MAX_VALUE.toInt())
        val result = request(
            machineId,
            Command.OpenTerminal(sessionId, cols.toUShort(), rows.toUShort(), sizePolicy),
        )
        require(result is CommandResult.TerminalOpened) { "unexpected open terminal response" }
        return terminals[result.streamId]
            ?: throw RelayException("terminal stream was not registered before output")
    }

    internal suspend fun sendTerminalInput(machineId: String, streamId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_TERMINAL_CHUNK_BYTES) { "terminal input chunk exceeds protocol limit" }
        sendPayload(
            machineId,
            ClientPayload.TerminalInput(streamId, RemuxCrypto.encodeBase64Url(bytes)),
        )
    }

    internal suspend fun resizeTerminal(
        machineId: String,
        streamId: String,
        cols: UShort,
        rows: UShort,
    ) {
        sendPayload(machineId, ClientPayload.TerminalResize(streamId, cols, rows))
    }

    internal suspend fun refreshTerminal(machineId: String, streamId: String) {
        sendPayload(machineId, ClientPayload.TerminalRefresh(streamId))
    }

    internal suspend fun selectTerminalWindow(
        machineId: String,
        streamId: String,
        windowId: String,
    ) {
        sendPayload(
            machineId,
            ClientPayload.TerminalSelectWindow(streamId, windowId),
        )
    }

    internal suspend fun detachTerminal(machineId: String, streamId: String) {
        terminals.remove(streamId)
        if (status.value.phase == ConnectionPhase.CONNECTED) {
            runCatching {
                sendPayload(machineId, ClientPayload.TerminalDetach(streamId))
            }
        }
    }

    private fun openSocket(isReconnect: Boolean) {
        val endpoint = "${profile.relayUrl.trimEnd('/')}/ws/client"
        mutableStatus.value = RelayStatus(
            if (isReconnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
        )
        val request = Request.Builder().url(endpoint).build()
        val listener = ClientWebSocketListener()
        socket = httpClient.newWebSocket(request, listener)
    }

    private inner class ClientWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val sent = webSocket.send(
                ProtocolCodec.encodeWire(
                    WireMessage.ClientHello(
                        protocol = PROTOCOL_VERSION,
                        token = profile.clientToken,
                        clientId = clientId,
                        clientName = clientName,
                    ),
                ),
            )
            if (!sent) failCurrent(webSocket, RelayException("failed to send relay hello"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleWire(webSocket, ProtocolCodec.decodeWire(text))
            } catch (error: Exception) {
                webSocket.close(1002, "invalid protocol message")
                failCurrent(webSocket, RelayException("invalid relay message", error))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket, "Relay closed the connection ($code): $reason")
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            failCurrent(webSocket, RelayException("Relay connection failed: ${error.message}", error))
        }
    }

    private fun handleWire(webSocket: WebSocket, message: WireMessage) {
        when (message) {
            is WireMessage.Ready -> {
                synchronized(lock) {
                    if (socket !== webSocket) return
                    retrySeconds = 1
                }
                mutableStatus.value = RelayStatus(ConnectionPhase.CONNECTED)
                startHeartbeat(webSocket)
            }
            is WireMessage.MachineSnapshot -> {
                mutableMachines.value = message.machines.associateBy(MachineInfo::id)
            }
            is WireMessage.MachineOnline -> {
                mutableMachines.value = mutableMachines.value + (message.machine.id to message.machine)
            }
            is WireMessage.MachineOffline -> {
                mutableMachines.value = mutableMachines.value - message.machineId
            }
            is WireMessage.DeliverToClient -> handleAgentPayload(message)
            is WireMessage.Ping -> webSocket.send(ProtocolCodec.encodeWire(WireMessage.Pong(message.nonce)))
            is WireMessage.Pong -> Unit
            is WireMessage.RelayError -> {
                val fatal = message.code in setOf("unauthorized", "protocol_mismatch", "bad_hello")
                if (fatal) {
                    synchronized(lock) { desired = false }
                    mutableStatus.value = RelayStatus(
                        ConnectionPhase.ERROR,
                        "${message.code}: ${message.message}",
                    )
                    webSocket.close(1008, message.code)
                } else {
                    failCurrent(webSocket, RelayException("${message.code}: ${message.message}"))
                }
            }
            is WireMessage.ClientHello,
            is WireMessage.RouteToAgent,
            -> throw RelayException("unexpected client-side wire message")
        }
    }

    private fun handleAgentPayload(message: WireMessage.DeliverToClient) {
        when (val payload = message.payload) {
            is AgentPayload.Response -> {
                val pending = pendingRequests[payload.requestId] ?: return
                if (payload.result is CommandResult.TerminalOpened && pending.command is Command.OpenTerminal) {
                    val opened = payload.result
                    terminals[opened.streamId] = TerminalHandle(
                        machineId = pending.machineId,
                        sessionId = pending.command.sessionId,
                        streamId = opened.streamId,
                        ignoreSize = opened.ignoreSize,
                        relayClient = this,
                    )
                }
                pending.result.complete(payload.result)
            }
            is AgentPayload.RequestError -> {
                pendingRequests[payload.requestId]?.result?.completeExceptionally(
                    AgentException(payload.code, payload.message),
                )
            }
            is AgentPayload.TerminalOutput -> {
                val bytes = RemuxCrypto.decodeBase64Url(payload.data)
                require(bytes.size <= MAX_TERMINAL_CHUNK_BYTES) {
                    "terminal output chunk exceeds protocol limit"
                }
                val terminal = terminals[payload.streamId] ?: return
                if (!terminal.deliver(bytes)) {
                    terminals.remove(payload.streamId, terminal)
                    terminal.markOverflow()
                    scope.launch {
                        runCatching { detachTerminal(message.machineId, payload.streamId) }
                    }
                }
            }
            is AgentPayload.TerminalClosed -> {
                terminals.remove(payload.streamId)?.markClosed(payload.reason, payload.exitCode)
            }
        }
    }

    private suspend fun sendPayload(machineId: String, payload: ClientPayload) {
        check(status.value.phase == ConnectionPhase.CONNECTED) { "relay is not connected" }
        val current = synchronized(lock) { socket }
            ?: throw RelayException("relay socket is unavailable")
        check(
            current.send(
                ProtocolCodec.encodeWire(WireMessage.RouteToAgent(machineId, payload)),
            ),
        ) { "relay rejected outgoing message" }
    }

    private fun startHeartbeat(current: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(20_000)
                val stillCurrent = synchronized(lock) { socket === current }
                if (!stillCurrent || status.value.phase != ConnectionPhase.CONNECTED) return@launch
                current.send(ProtocolCodec.encodeWire(WireMessage.Ping(System.nanoTime().toULong())))
            }
        }
    }

    private fun failCurrent(current: WebSocket, error: RelayException) {
        mutableStatus.value = RelayStatus(ConnectionPhase.ERROR, error.message)
        disconnected(current, error.message ?: "Relay connection failed")
    }

    private fun disconnected(current: WebSocket, reason: String) {
        val shouldReconnect = synchronized(lock) {
            if (socket !== current) return
            socket = null
            heartbeat?.cancel()
            desired
        }
        pendingRequests.values.forEach { it.result.completeExceptionally(RelayException(reason)) }
        pendingRequests.clear()
        terminals.values.forEach { it.markConnectionLost(reason) }
        if (!shouldReconnect) {
            if (mutableStatus.value.phase != ConnectionPhase.ERROR) {
                mutableStatus.value = RelayStatus(ConnectionPhase.STOPPED, reason)
            }
            return
        }
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnect?.isActive == true) return
        val delaySeconds = retrySeconds
        retrySeconds = min(30L, retrySeconds * 2)
        mutableStatus.value = RelayStatus(ConnectionPhase.RECONNECTING, reason, delaySeconds)
        reconnect = scope.launch(Dispatchers.IO) {
            delay(delaySeconds * 1_000)
            synchronized(lock) {
                if (desired && socket == null) openSocket(isReconnect = true)
            }
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            desired = false
            reconnect?.cancel()
            heartbeat?.cancel()
            socket.also { socket = null }
        }
        current?.close(1000, "Android client closed")
        pendingRequests.values.forEach {
            it.result.completeExceptionally(RelayException("client closed"))
        }
        pendingRequests.clear()
        terminals.values.forEach { it.markConnectionLost("Client closed") }
        terminals.clear()
        mutableStatus.value = RelayStatus(ConnectionPhase.STOPPED)
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            // The relay serves an ephemeral self-signed certificate by default
            // (mirroring the Rust agent/client, which also skip certificate
            // validation). Protocol v2 removed end-to-end payload encryption:
            // TLS here encrypts the transport but does NOT authenticate the
            // relay, so the relay operator — or an active MITM anywhere on the
            // TLS path — can read and modify terminal content. On untrusted
            // networks, front the relay with a real certificate instead of
            // relying on this trust-all client.
            val trustAll = object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), null)
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun validateRelayUrl(url: String) {
            require(url.startsWith("ws://") || url.startsWith("wss://")) {
                "relay URL must start with ws:// or wss://"
            }
            require(BuildConfig.DEBUG || url.startsWith("wss://")) {
                "Release builds require a wss:// relay URL"
            }
        }
    }
}
