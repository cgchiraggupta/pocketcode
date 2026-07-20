package com.remotedev.pocketcode.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders PTY output through a real terminal emulator (xterm.js in a WebView)
 * instead of hand-rolled line-by-line text parsing. Fixes cursor-addressed
 * redraws (status bars, TUI menus) that a flat append-only renderer can't
 * represent -- xterm.js tracks a real 2D cell grid + cursor position.
 *
 * Raw text is bridged as base64 UTF-8 rather than interpolated into JS
 * directly, since the PTY stream is full of quotes/backslashes/control
 * bytes that would otherwise have to be hand-escaped for evaluateJavascript.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermTerminalView(
    tabId: String,
    raw: String,
    onInput: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Tracks (tabId, lastRawWritten) for the WebView currently alive, so we
    // know whether a new `raw` value is a same-tab incremental append (write
    // just the delta) or a tab switch / cap-driven truncation (reset the
    // terminal and replay the full buffer). A plain length comparison isn't
    // enough: once the upstream buffer hits its cap and starts dropping old
    // content from the front, length can stay constant (or even look larger)
    // while the actual text shifted -- a startsWith check catches that.
    val state = remember { WrittenState() }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xFF0F0F10.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(
                    JsBridge(
                        inputCallback = { data -> mainHandler.post { onInput(data) } },
                        resizeCallback = { cols, rows -> mainHandler.post { onResize(cols, rows) } },
                    ),
                    "Android",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        // `update` can run before terminal.html defines its bridge
                        // functions. Replay the latest requested tab only after that
                        // page is ready, otherwise a tab switch can leave xterm blank.
                        state.pageReady = true
                        state.renderPending(view)
                    }
                }
                loadUrl("file:///android_asset/xterm/terminal.html")
                state.pageReady = false
                state.tabId = null
                state.lastRaw = ""
            }
        },
        update = { webView ->
            state.pendingTabId = tabId
            state.pendingRaw = raw
            state.renderPending(webView)
        },
    )
}

private class WrittenState {
    var pageReady = false
    var tabId: String? = null
    var lastRaw: String = ""
    var pendingTabId: String? = null
    var pendingRaw: String = ""
    private var renderQueued = false

    fun renderPending(webView: WebView) {
        if (!pageReady) return
        if (pendingTabId == null || renderQueued) return

        // PTY output often arrives in many small WebSocket frames. Batching
        // them to one WebView update per display frame avoids visible redraw
        // flicker while an agent is streaming.
        renderQueued = true
        webView.postDelayed({
            renderQueued = false
            renderLatest(webView)
        }, RENDER_BATCH_DELAY_MS)
    }

    private fun renderLatest(webView: WebView) {
        val nextTabId = pendingTabId ?: return
        if (!pageReady) return

        when {
            tabId != nextTabId -> {
                tabId = nextTabId
                lastRaw = pendingRaw
                webView.evaluateJavascript("window.resetAndWrite('${b64(pendingRaw)}')", null)
            }
            pendingRaw == lastRaw -> Unit
            pendingRaw.startsWith(lastRaw) -> {
                val delta = pendingRaw.substring(lastRaw.length)
                lastRaw = pendingRaw
                webView.evaluateJavascript("window.writeChunk('${b64(delta)}')", null)
            }
            else -> shiftedAppendDelta(lastRaw, pendingRaw)?.let { delta ->
                // Both Android and the server retain a capped raw buffer. When
                // its front is dropped, keep xterm alive and append only the
                // new tail instead of resetting the whole terminal per chunk.
                lastRaw = pendingRaw
                webView.evaluateJavascript("window.writeChunk('${b64(delta)}')", null)
            } ?: run {
                // The server replay replaced history or the buffer genuinely
                // changed. A full resync is required in this uncommon path.
                lastRaw = pendingRaw
                webView.evaluateJavascript("window.resetAndWrite('${b64(pendingRaw)}')", null)
            }
        }
    }
}

private const val RENDER_BATCH_DELAY_MS = 16L

/**
 * Finds the new suffix when a capped buffer dropped a prefix. Returns null for
 * a true replacement, which must still be replayed from scratch.
 */
private fun shiftedAppendDelta(previous: String, next: String): String? {
    if (previous.length != next.length || previous.isEmpty()) return null

    val probeLength = minOf(64, next.length)
    val probe = next.substring(0, probeLength)
    var start = previous.indexOf(probe)
    while (start >= 0) {
        val overlapLength = previous.length - start
        if (previous.regionMatches(start, next, 0, overlapLength)) {
            return next.substring(overlapLength)
        }
        start = previous.indexOf(probe, start + 1)
    }
    return null
}

private fun b64(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private class JsBridge(
    private val inputCallback: (String) -> Unit,
    private val resizeCallback: (Int, Int) -> Unit,
) {
    @JavascriptInterface
    fun onInput(data: String) {
        inputCallback(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        resizeCallback(cols, rows)
    }
}
