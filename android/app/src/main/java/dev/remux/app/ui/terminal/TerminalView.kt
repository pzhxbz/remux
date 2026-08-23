package dev.remux.app.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.accessibility.AccessibilityManager
import androidx.webkit.WebViewAssetLoader
import dev.remux.app.BuildConfig
import dev.remux.app.protocol.RemuxCrypto
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class TerminalViewport(
    val atBottom: Boolean = true,
    val distance: Int = 0,
    val unread: Int = 0,
)

data class TerminalModes(
    val alternateScreen: Boolean = false,
    val bracketedPaste: Boolean = false,
    val mouseTracking: String = "none",
)

interface TerminalViewListener {
    fun onReady(cols: Int, rows: Int)
    fun onInput(bytes: ByteArray)
    fun onResize(cols: Int, rows: Int)
    fun onViewportChanged(viewport: TerminalViewport)
    fun onModesChanged(modes: TerminalModes)
    fun onTitleChanged(title: String)
    fun onFontSizeChanged(value: Int)
    fun onError(message: String)
}

@SuppressLint("SetJavaScriptEnabled")
class TerminalView(context: Context) : FrameLayout(context) {
    private data class QueuedOutput(val id: Long, val data: String, val byteCount: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val outputIds = AtomicLong()
    private val outputQueue = ArrayDeque<QueuedOutput>()
    private val pendingCommands = ArrayDeque<String>()
    private val accessibilityManager = context.getSystemService(
        Context.ACCESSIBILITY_SERVICE,
    ) as AccessibilityManager
    private val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener {
        updateScreenReaderMode()
    }
    private val touchExplorationStateListener =
        AccessibilityManager.TouchExplorationStateChangeListener {
            updateScreenReaderMode()
        }
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    private val webView = WebView(context)
    private var nativePort: WebMessagePort? = null
    private var inFlight: QueuedOutput? = null
    private var queuedBytes = 0
    private var ready = false
    private var destroyed = false

    var listener: TerminalViewListener? = null

    init {
        setBackgroundColor(Color.rgb(11, 16, 20))
        contentDescription = "Remote terminal"
        isFocusable = true
        isFocusableInTouchMode = true
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        configureWebView()
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
        accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationStateListener)
        updateScreenReaderMode()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl(TERMINAL_URL)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val copy = bytes.copyOf()
        mainHandler.post {
            if (destroyed) return@post
            if (queuedBytes + copy.size > MAX_RENDER_QUEUE_BYTES) {
                listener?.onError("Terminal renderer queue exceeded 4 MiB; reattach for a clean redraw")
                return@post
            }
            outputQueue.addLast(
                QueuedOutput(
                    id = outputIds.incrementAndGet(),
                    data = RemuxCrypto.encodeBase64Url(copy),
                    byteCount = copy.size,
                ),
            )
            queuedBytes += copy.size
            drainOutput()
        }
    }

    fun focusTerminal() = sendCommand(JSONObject().put("type", "focus"))

    fun fit() = sendCommand(JSONObject().put("type", "fit"))

    fun scrollToBottom() = sendCommand(JSONObject().put("type", "scroll_to_bottom"))

    fun scrollLines(lines: Int) = sendCommand(
        JSONObject().put("type", "scroll_lines").put("lines", lines),
    )

    fun setFontSize(value: Int) = sendCommand(
        JSONObject().put("type", "set_font_size").put("value", value.coerceIn(8, 28)),
    )

    fun setPointerMode(application: Boolean) = sendCommand(
        JSONObject()
            .put("type", "set_pointer_mode")
            .put("value", if (application) "application" else "history"),
    )

    fun setScreenReader(enabled: Boolean) = sendCommand(
        JSONObject().put("type", "set_screen_reader").put("enabled", enabled),
    )

    fun destroy() {
        if (destroyed) return
        destroyed = true
        nativePort?.close()
        nativePort = null
        outputQueue.clear()
        pendingCommands.clear()
        inFlight = null
        queuedBytes = 0
        ready = false
        accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateListener)
        accessibilityManager.removeTouchExplorationStateChangeListener(touchExplorationStateListener)
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        removeAllViews()
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.rgb(11, 16, 20))
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            displayZoomControls = false
            safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || uri.host != ASSET_HOST
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == TERMINAL_URL && nativePort == null) openMessageChannel()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    listener?.onError("Terminal renderer: ${consoleMessage.message()}")
                }
                return true
            }
        }
    }

    private fun openMessageChannel() {
        val ports = webView.createWebMessageChannel()
        nativePort = ports[0].apply {
            setWebMessageCallback(
                object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        handleMessage(message.data ?: return)
                    }
                },
                mainHandler,
            )
        }
        webView.postWebMessage(
            WebMessage("remux-init", arrayOf(ports[1])),
            Uri.parse(APP_ORIGIN),
        )
    }

    private fun handleMessage(value: String) {
        try {
            val message = JSONObject(value)
            when (message.getString("type")) {
                "ready" -> {
                    ready = true
                    flushPendingCommands()
                    listener?.onReady(message.getInt("cols"), message.getInt("rows"))
                    drainOutput()
                }
                "input" -> listener?.onInput(RemuxCrypto.decodeBase64Url(message.getString("data")))
                "resize" -> listener?.onResize(message.getInt("cols"), message.getInt("rows"))
                "viewport" -> listener?.onViewportChanged(
                    TerminalViewport(
                        atBottom = message.getBoolean("atBottom"),
                        distance = message.getInt("distance"),
                        unread = message.getInt("unread"),
                    ),
                )
                "modes" -> listener?.onModesChanged(
                    TerminalModes(
                        alternateScreen = message.getBoolean("alternateScreen"),
                        bracketedPaste = message.getBoolean("bracketedPaste"),
                        mouseTracking = message.getString("mouseTracking"),
                    ),
                )
                "title" -> listener?.onTitleChanged(message.getString("title"))
                "font_size" -> listener?.onFontSizeChanged(message.getInt("value"))
                "output_ack" -> acknowledgeOutput(message.getLong("id"))
                "error" -> listener?.onError(message.getString("message"))
                "pointer_mode" -> Unit
                else -> listener?.onError("Unsupported terminal renderer event")
            }
        } catch (error: Exception) {
            listener?.onError("Invalid terminal renderer event: ${error.message}")
        }
    }

    private fun drainOutput() {
        if (!ready || nativePort == null || inFlight != null) return
        val next = outputQueue.pollFirst() ?: return
        inFlight = next
        sendCommand(
            JSONObject()
                .put("type", "output")
                .put("id", next.id)
                .put("data", next.data),
        )
    }

    private fun acknowledgeOutput(id: Long) {
        val current = inFlight
        if (current == null || current.id != id) {
            listener?.onError("Terminal renderer acknowledged an unexpected output chunk")
            return
        }
        queuedBytes -= current.byteCount
        inFlight = null
        drainOutput()
    }

    private fun sendCommand(message: JSONObject) {
        val serialized = message.toString()
        mainHandler.post {
            if (destroyed) return@post
            val port = nativePort
            if (!ready || port == null) {
                if (pendingCommands.size >= MAX_PENDING_COMMANDS) {
                    listener?.onError("Terminal renderer command queue overflowed")
                } else {
                    pendingCommands.addLast(serialized)
                }
                return@post
            }
            port.postMessage(WebMessage(serialized))
        }
    }

    private fun flushPendingCommands() {
        val port = nativePort ?: return
        while (pendingCommands.isNotEmpty()) {
            port.postMessage(WebMessage(pendingCommands.removeFirst()))
        }
    }

    private fun updateScreenReaderMode() {
        setScreenReader(
            accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled,
        )
    }

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val APP_ORIGIN = "https://$ASSET_HOST"
        const val TERMINAL_URL = "$APP_ORIGIN/assets/terminal/index.html"
        const val MAX_RENDER_QUEUE_BYTES = 4 * 1024 * 1024
        const val MAX_PENDING_COMMANDS = 64
    }
}
