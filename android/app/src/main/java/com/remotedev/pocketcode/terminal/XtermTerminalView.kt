package com.remotedev.pocketcode.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
                        onInput = { data -> mainHandler.post { onInput(data) } },
                        onResize = { cols, rows -> mainHandler.post { onResize(cols, rows) } },
                    ),
                    "Android",
                )
                loadUrl("file:///android_asset/xterm/terminal.html")
                state.tabId = null
                state.lastRaw = ""
            }
        },
        update = { webView ->
            when {
                state.tabId != tabId -> {
                    state.tabId = tabId
                    state.lastRaw = raw
                    webView.evaluateJavascript("window.resetAndWrite && window.resetAndWrite('${b64(raw)}')", null)
                }
                raw == state.lastRaw -> { /* no change */ }
                raw.startsWith(state.lastRaw) -> {
                    val delta = raw.substring(state.lastRaw.length)
                    state.lastRaw = raw
                    webView.evaluateJavascript("window.writeChunk && window.writeChunk('${b64(delta)}')", null)
                }
                else -> {
                    // Not a simple append (cap-driven truncation from the front, or
                    // a replay that replaced history) -- full resync is simplest.
                    state.lastRaw = raw
                    webView.evaluateJavascript("window.resetAndWrite && window.resetAndWrite('${b64(raw)}')", null)
                }
            }
        },
    )
}

private class WrittenState {
    var tabId: String? = null
    var lastRaw: String = ""
}

private fun b64(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private class JsBridge(
    private val onInput: (String) -> Unit,
    private val onResize: (Int, Int) -> Unit,
) {
    @JavascriptInterface
    fun onInput(data: String) {
        onInput(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        onResize(cols, rows)
    }
}
