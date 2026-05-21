package com.gsvn.aabrowser.web

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

data class BrowserCallbacks(
    val onProgressChange: (Int) -> Unit = {},
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    val onExitFullscreen: () -> Unit = {},
    val onPermissionRequest: (PermissionRequest) -> Unit = { it.deny() }
)

fun configureWebView(
    webView: WebView,
    callbacks: BrowserCallbacks = BrowserCallbacks()
) {
    with(webView) {
        setBackgroundColor(Color.BLACK)

        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false

        // Cho phép WebView nhận focus để IME hoạt động
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()

        WebView.setWebContentsDebuggingEnabled(false)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                offscreenPreRaster = true
            }

            // Dùng UA mobile Chrome để YouTube hoạt động tốt
            userAgentString = MOBILE_CHROME_UA
            useWideViewPort = false
            loadWithOverviewMode = false
        }

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(this, true)
        }

        // Bridge để JS gọi native show/hide keyboard
        addJavascriptInterface(ImeKeyboardBridge(this), "ImeKeyboardBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                this,
                DOCUMENT_START_JS,
                setOf("*")
            )
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val blocked = AdBlocker.shouldBlock(request.url?.toString())
                if (blocked != null) return blocked
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme?.lowercase() ?: return false
                // Chỉ load http/https, bỏ qua các scheme khác (intent://, market://, ...)
                return scheme !in setOf("http", "https", "about", "javascript", "data")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)

                // Fallback for older WebViews if DOCUMENT_START_SCRIPT is not supported
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(DOCUMENT_START_JS, null)
                }

                // Inject keyboard trigger: khi click/focus vào input → gọi native show IME
                view.evaluateJavascript(KEYBOARD_TRIGGER_JS, null)

                // Inject YouTube ad-blocking JS
                val pageUrl = url?.lowercase() ?: ""
                if (pageUrl.contains("youtube.com") || pageUrl.contains("youtu.be")) {
                    view.evaluateJavascript(AdBlocker.YOUTUBE_AD_BLOCK_JS, null)
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChange(newProgress)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    callbacks.onEnterFullscreen(view, callback)
                } else {
                    super.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
                super.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return

                val allowed = setOf(
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE
                )

                val grantable = request.resources.filter { it in allowed }.toTypedArray()

                if (grantable.isEmpty()) {
                    request.deny()
                    return
                }

                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
                    callbacks.onPermissionRequest(request)
                } else {
                    this@with.post { request.grant(grantable) }
                }
            }
        }
    }
}

/**
 * JavaScript interface để JS trong WebView gọi native show/hide bàn phím.
 * Cần thiết trên Android Automotive vì một số head unit không tự show IME
 * khi WebView focus vào input field.
 */
private class ImeKeyboardBridge(private val webView: WebView) {

    @JavascriptInterface
    fun showKeyboard() {
        webView.post {
            webView.requestFocus()
            val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @JavascriptInterface
    fun hideKeyboard() {
        webView.post {
            val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(webView.windowToken, 0)
        }
    }
}

fun WebView.releaseCompletely() {
    stopLoading()
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClient()
    destroy()
}

private const val CHROME_VERSION = "144.0.0.0"
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VERSION} Mobile Safari/537.36"

private const val KEYBOARD_TRIGGER_JS = """
    (function() {
        if (window.__imeInjected) return;
        window.__imeInjected = true;

        function isInputEl(el) {
            if (!el) return false;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
            if (el.isContentEditable) return true;
            return false;
        }

        function onFocusIn(e) {
            if (isInputEl(e.target)) {
                if (window.ImeKeyboardBridge) {
                    window.ImeKeyboardBridge.showKeyboard();
                }
            }
        }

        function onFocusOut(e) {
            if (isInputEl(e.target)) {
                if (window.ImeKeyboardBridge) {
                    window.ImeKeyboardBridge.hideKeyboard();
                }
            }
        }

        document.addEventListener('focusin', onFocusIn, true);
        document.addEventListener('focusout', onFocusOut, true);
    })();
"""

private const val DOCUMENT_START_JS = """
    (function() {
        if (window.__bgPlaybackInjected) return;
        window.__bgPlaybackInjected = true;

        // 1. Visibility and Focus Spoofing
        Object.defineProperty(document, 'visibilityState', {
            get: function() { return 'visible'; },
            configurable: true
        });
        Object.defineProperty(document, 'hidden', {
            get: function() { return false; },
            configurable: true
        });
        
        // Spoof document.hasFocus
        document.hasFocus = function() { return true; };

        // Block visibilitychange events from pausing playback
        document.addEventListener('visibilitychange', function(e) {
            e.stopImmediatePropagation();
        }, true);

        // Block blur and focus-out events on window/document to prevent auto-pause on focus loss
        window.addEventListener('blur', function(e) {
            e.stopImmediatePropagation();
        }, true);
        window.addEventListener('focusout', function(e) {
            e.stopImmediatePropagation();
        }, true);
        window.addEventListener('pagehide', function(e) {
            e.stopImmediatePropagation();
        }, true);

        // 2. Interaction Tracking to control autoplay
        window.__userInteracted = false;
        
        function markInteraction() {
            window.__userInteracted = true;
        }
        
        document.addEventListener('click', markInteraction, true);
        document.addEventListener('touchstart', markInteraction, true);
        document.addEventListener('mousedown', markInteraction, true);
        document.addEventListener('keydown', markInteraction, true);

        // 3. Helper to detect feed videos or preview clips
        function isFeedOrPreviewVideo(video) {
            var parent = video.parentElement;
            while (parent) {
                var tag = parent.tagName ? parent.tagName.toLowerCase() : '';
                var cls = parent.className || '';
                var id = parent.id || '';
                
                if (tag === 'ytm-inline-playback-renderer' ||
                    tag === 'ytd-video-preview' ||
                    cls.indexOf('ytp-ad-') !== -1 ||
                    cls.indexOf('ytd-video-preview') !== -1 ||
                    cls.indexOf('ytd-moving-thumbnail-renderer') !== -1 ||
                    cls.indexOf('video-preview') !== -1 ||
                    id.indexOf('preview') !== -1
                ) {
                    return true;
                }
                parent = parent.parentElement;
            }
            
            // If the path is '/' or is a feed/search results page, all videos in it are feeds
            var path = window.location.pathname;
            if (path === '/' || path === '' || path.indexOf('/results') !== -1 || path.indexOf('/feed/') !== -1) {
                return true;
            }
            
            // If it is main, it should have the html5-main-video class
            if (video.classList && video.classList.contains('html5-main-video')) {
                return false;
            }
            
            return false;
        }

        // 4. Override HTMLVideoElement.prototype.play and mute behaviors
        var originalPlay = HTMLVideoElement.prototype.play;
        
        var mutedDesc = Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype, 'muted');
        if (mutedDesc && mutedDesc.set) {
            var originalMutedSetter = mutedDesc.set;
            Object.defineProperty(HTMLVideoElement.prototype, 'muted', {
                get: mutedDesc.get,
                set: function(val) {
                    // Force mute if user has not interacted yet
                    if (!window.__userInteracted && !val) {
                        return originalMutedSetter.call(this, true);
                    }
                    return originalMutedSetter.call(this, val);
                },
                configurable: true
            });
        }

        HTMLVideoElement.prototype.play = function() {
            var video = this;

            // Block feed or preview videos from playing
            if (isFeedOrPreviewVideo(video)) {
                try { video.pause(); } catch(e) {}
                return Promise.resolve();
            }

            // Fresh launch: mute and prevent autoplay of main video until first user click
            if (!window.__userInteracted) {
                try {
                    video.muted = true;
                    video.pause();
                } catch(e) {}
                return Promise.resolve();
            }

            return originalPlay.call(video);
        };

        // 5. Periodic check to pause feed clips and mute main video on fresh launch
        function enforceAutoplayPolicies() {
            var videos = document.querySelectorAll('video');
            videos.forEach(function(video) {
                if (isFeedOrPreviewVideo(video)) {
                    if (!video.paused) {
                        try { video.pause(); } catch(e) {}
                    }
                } else {
                    if (!window.__userInteracted) {
                        if (!video.muted) {
                            try { video.muted = true; } catch(e) {}
                        }
                        if (!video.paused) {
                            try { video.pause(); } catch(e) {}
                        }
                    }
                }
            });
        }

        setInterval(enforceAutoplayPolicies, 250);

        // 6. Track user's own mute intent
        function trackMute() {
            var videos = document.querySelectorAll('video');
            videos.forEach(function(v) {
                if (v._muteListenerAdded) return;
                v._muteListenerAdded = true;
                v._userMuted = v.muted;
                v.addEventListener('volumechange', function() {
                    if (!v._adMuting) v._userMuted = v.muted;
                });
            });
        }
        setInterval(trackMute, 2000);
    })();
"""