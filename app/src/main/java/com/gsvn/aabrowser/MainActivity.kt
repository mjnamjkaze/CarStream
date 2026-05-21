package com.gsvn.aabrowser

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import com.google.android.material.color.DynamicColors
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gsvn.aabrowser.databinding.ActivityMainBinding
import com.gsvn.aabrowser.web.BrowserCallbacks
import com.gsvn.aabrowser.web.configureWebView
import com.gsvn.aabrowser.web.releaseCompletely

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webView: android.webkit.WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var isBackgroundPlaybackActive: Boolean = false

    // ── Audio Focus ─────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus (e.g. after a phone call) — resume playback
                webView?.onResume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — another app (e.g. Maps, Phone) fully took over.
                // We DON'T pause here because BackgroundPlaybackService keeps us alive.
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Brief loss (notification sound, etc.) — ignore, keep playing.
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // System wants us to duck — ignore, YouTube manages its own volume.
            }
        }
    }

    private val stopPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackgroundPlaybackService.ACTION_STOP_PLAYBACK) {
                isBackgroundPlaybackActive = false
                webView?.onPause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Pick best display mode (refresh rate cao nhất)
        val best = display?.supportedModes?.maxWithOrNull(
            compareBy({ it.refreshRate }, { it.physicalWidth.toLong() * it.physicalHeight })
        )
        best?.let { window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId } }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        setupWebView()
        setupBackPressHandling()
        ensureNotificationPermission()
        registerReceiver(
            stopPlaybackReceiver,
            IntentFilter(BackgroundPlaybackService.ACTION_STOP_PLAYBACK),
            RECEIVER_NOT_EXPORTED
        )
    }

    // ── Audio Focus ─────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false) // Let YouTube manage its own volume
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()

        audioManager.requestAudioFocus(audioFocusRequest!!)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    override fun onResume() {
        super.onResume()
        val wasBackgroundActive = isBackgroundPlaybackActive
        if (isBackgroundPlaybackActive) {
            BackgroundPlaybackService.stop(this)
            isBackgroundPlaybackActive = false
        }
        // Re-request audio focus in case another app took it while we were away
        requestAudioFocus()
        if (!wasBackgroundActive) {
            webView?.onResume()
        }
    }

    override fun onPause() {
        exitFullscreen()
        isBackgroundPlaybackActive = true
        BackgroundPlaybackService.start(this)
        super.onPause()
        // NOTE: do NOT abandon audio focus here — BackgroundPlaybackService
        // keeps the WebView alive and audio must continue playing in background.
    }

    override fun onDestroy() {
        BackgroundPlaybackService.stop(this)
        isBackgroundPlaybackActive = false
        abandonAudioFocus()
        runCatching { unregisterReceiver(stopPlaybackReceiver) }
        exitFullscreen()
        binding.webView.releaseCompletely()
        webView = null
        super.onDestroy()
    }

    // ── WebView setup ──────────────────────────────────────────────

    private fun setupWebView() {
        val callbacks = BrowserCallbacks(
            onProgressChange = { progress ->
                runOnUiThread {
                    binding.progressIndicator.visibility =
                        if (progress in 1..99) View.VISIBLE else View.GONE
                    if (progress in 1..99) binding.progressIndicator.setProgressCompat(progress, true)
                }
            },
            onEnterFullscreen = { view, callback ->
                runOnUiThread { enterFullscreen(view, callback) }
            },
            onExitFullscreen = {
                runOnUiThread { exitFullscreen(fromWebChrome = true) }
            },
            onPermissionRequest = { request ->
                runOnUiThread { handlePermissionRequest(request) }
            }
        )

        webView = binding.webView
        webView?.let { view ->
            configureWebView(view, callbacks)
            view.loadUrl(YOUTUBE_URL)
        }
    }

    // ── Fullscreen ─────────────────────────────────────────────────

    private fun isInFullscreen(): Boolean = customView != null

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customViewCallback = callback
        binding.webView.visibility = View.INVISIBLE
        binding.fullscreenContainer.apply {
            visibility = View.VISIBLE
            removeAllViews()
            addView(view, FrameLayout.LayoutParams(-1, -1))
            bringToFront()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, binding.fullscreenContainer)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen(fromWebChrome: Boolean = false) {
        if (customView == null) return
        binding.fullscreenContainer.apply { removeAllViews(); visibility = View.GONE }
        binding.webView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        if (!fromWebChrome) callback?.onCustomViewHidden()
    }

    // ── Back press ─────────────────────────────────────────────────

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                isInFullscreen() -> exitFullscreen()
                webView?.canGoBack() == true -> webView?.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    // ── Permissions ────────────────────────────────────────────────

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS
        )
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val allowed = setOf(
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE
        )
        val grantable = request.resources.filter { it in allowed }.toTypedArray()
        if (grantable.isEmpty()) { request.deny(); return }

        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                request.grant(grantable)
            } else {
                pendingPermissionRequest = request
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO
                )
            }
        } else {
            request.grant(grantable)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request != null) {
                if (granted) {
                    val allowed = setOf(
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    )
                    val grantable = request.resources.filter { it in allowed }.toTypedArray()
                    if (grantable.isNotEmpty()) request.grant(grantable) else request.deny()
                } else {
                    request.deny()
                }
            }
        }
    }

    companion object {
        private const val YOUTUBE_URL = "https://www.youtube.com"
        private const val RC_NOTIFICATIONS = 1101
        private const val RC_AUDIO = 1102
    }
}