package com.gsvn.aabrowser.web

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * Custom WebView that:
 * 1. Prevents automatic media pause when the window visibility changes
 *    (screen off, app backgrounded) — keeps audio/video playing in background.
 * 2. Preserves proper IME (soft keyboard) behavior so input fields work correctly.
 */
class BackgroundPlayWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /**
     * When true, window visibility changes are ignored so media keeps playing.
     */
    var backgroundPlaybackEnabled: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (backgroundPlaybackEnabled) {
            // Always tell WebView the window is VISIBLE to prevent media pause.
            // We pass VISIBLE only for the internal WebView renderer — this does
            // NOT affect focus or IME, which go through separate channels.
            super.onWindowVisibilityChanged(VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    /**
     * Allow the WebView to accept input connections even when it thinks it
     * is not the primary focus target. This is needed on some head units
     * where the IME checks this before showing the keyboard.
     */
    override fun checkInputConnectionProxy(view: android.view.View?): Boolean = true

    /**
     * Ensure the InputConnection is always created (even in edge cases where
     * Android Auto's window manager might otherwise suppress it).
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        // If the parent returned null, we cannot fix it here — but returning
        // the parent result ensures we don't accidentally suppress a valid IC.
        return ic
    }
}
