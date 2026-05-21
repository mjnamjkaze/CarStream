package com.gsvn.aabrowser.web

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {

    // Known ad-serving domains
    private val AD_DOMAINS = setOf(
        // Google Ads / DoubleClick
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "s0.2mdn.net",
        "www.googleadservices.com",
        "tpc.googlesyndication.com",
        "adservice.google.com",
        "adservice.google.co.uk",
        "adservice.google.ca",
        "adservice.google.com.au",
        "pagead-googlehosted.l.google.com",

        // YouTube ad subdomains
        "ads.youtube.com",
        "youtube.cleverads.vn",
        "ade.googlesyndication.com",

        // Generic ad networks
        "ad.turn.com",
        "ad.amgdgt.com",
        "cdn.adsafeprotected.com",
        "match.adsrvr.org",
        "pixel.adsafeprotected.com",

        // NOTE: analytics.youtube.com, fundingchoicesmessages.google.com,
        // crashlyticsreports-pa.googleapis.com are intentionally NOT blocked
        // because the YouTube player depends on them for stream authorization.
    )

    // YouTube-specific ad URL path patterns
    private val YOUTUBE_AD_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/get_midroll_info",
        "/ptracking",
        "/api/stats/qoe?adformat",
        "/youtubei/v1/player/ad_break",
    )

    /**
     * Check if a URL should be blocked as an ad request.
     * Returns a blank WebResourceResponse if blocked, null otherwise.
     */
    fun shouldBlock(url: String?): WebResourceResponse? {
        if (url.isNullOrBlank()) return null

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        // Check against ad domain list
        if (isAdDomain(host)) {
            return createEmptyResponse()
        }

        // Check YouTube-specific ad paths
        if (host.contains("youtube.com") || host.contains("googlevideo.com")) {
            val path = uri.path?.lowercase() ?: ""
            val query = uri.query?.lowercase() ?: ""

            // Block YouTube ad-specific paths
            for (adPath in YOUTUBE_AD_PATHS) {
                if (path.contains(adPath)) {
                    return createEmptyResponse()
                }
            }

            // Block googlevideo ad streams (video ads served via googlevideo.com)
            // ONLY block URLs with unambiguous ad markers.
            // WARNING: Do NOT block based on 'ctier' — it's a CDN routing param,
            //          not an ad indicator, and blocking it breaks legitimate streams.
            if (host.contains("googlevideo.com")) {
                if (query.contains("oad=") ||
                    url.contains("&ad_type=") ||
                    url.contains("&adformat=")
                ) {
                    return createEmptyResponse()
                }
            }
        }

        return null
    }

    private fun isAdDomain(host: String): Boolean {
        // Exact match
        if (host in AD_DOMAINS) return true
        // Subdomain match (e.g., sub.ad.doubleclick.net should also match)
        for (adDomain in AD_DOMAINS) {
            if (host.endsWith(".$adDomain")) return true
        }
        return false
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * JavaScript to inject on YouTube pages to auto-skip ads,
     * hide ad overlays, and remove ad banners.
     */
    val YOUTUBE_AD_BLOCK_JS = """
        (function() {
            if (window.__yt_adblock_injected) return;
            window.__yt_adblock_injected = true;

            // CSS to hide ad-related elements
            var style = document.createElement('style');
            style.textContent = [
                /* Pre-roll / mid-roll ad overlay */
                '.ytp-ad-module { display: none !important; }',
                '.ytp-ad-overlay-container { display: none !important; }',
                '.ytp-ad-text-overlay { display: none !important; }',
                '.ytp-ad-overlay-slot { display: none !important; }',
                '.ytp-ad-overlay-ad-info-button-container { display: none !important; }',
                '.ytp-ad-player-overlay { display: none !important; }',
                '.ytp-ad-player-overlay-instream-info { display: none !important; }',
                '.ytp-ad-image-overlay { display: none !important; }',

                /* Ad action buttons */
                '.ytp-ad-visit-advertiser-button { display: none !important; }',
                '.ytp-ad-button-icon { display: none !important; }',

                /* Banner / companion ads */
                '#player-ads { display: none !important; }',
                '#masthead-ad { display: none !important; }',
                '#banner-ad { display: none !important; }',
                'ytd-banner-promo-renderer { display: none !important; }',
                'ytd-statement-banner-renderer { display: none !important; }',
                'ytd-in-feed-ad-layout-renderer { display: none !important; }',
                'ytd-ad-slot-renderer { display: none !important; }',
                'ytd-rich-item-renderer:has(ytd-ad-slot-renderer) { display: none !important; }',
                'ytd-promoted-sparkles-web-renderer { display: none !important; }',
                'ytd-companion-slot-renderer { display: none !important; }',
                'ytd-promoted-video-renderer { display: none !important; }',
                'ytd-display-ad-renderer { display: none !important; }',
                'ytm-companion-ad-renderer { display: none !important; }',
                'ytm-promoted-sparkles-web-renderer { display: none !important; }',

                /* Mobile ad overlays */
                '.ytm-ad-break-module { display: none !important; }',
                '.ad-showing .video-ads { display: none !important; }',
                '.ad-interrupting .video-ads { display: none !important; }',

                /* Survey / feedback */
                'tp-yt-paper-dialog:has(.ytd-enforcement-message-view-model) { display: none !important; }',

                /* Premium / upsell prompts */
                'ytd-popup-container:has(a[href*="premium"]) { display: none !important; }',
                'ytd-mealbar-promo-renderer { display: none !important; }',

                /* Hide Autoplay Previews (Inline Playback) */
                'ytm-inline-playback-renderer { display: none !important; }',
                '.ytd-video-preview { display: none !important; }',
                '.ytd-moving-thumbnail-renderer { display: none !important; }',
                '.ytd-thumbnail-overlay-loading-preview-renderer { display: none !important; }',

                /* Hide Shorts Shelf / Reels / Pivot button */
                'ytm-reel-shelf-renderer { display: none !important; }',
                '.pivot-shorts { display: none !important; }',
                'ytm-pivot-bar-item-renderer:has(.pivot-shorts) { display: none !important; }',
                'ytm-pivot-bar-item-renderer:has(a[href*="/shorts/"]) { display: none !important; }',
                'ytm-video-with-context-renderer:has(ytm-thumbnail-overlay-time-status-renderer[data-style="SHORTS"]) { display: none !important; }',
                'ytm-video-with-context-renderer:has(a[href*="/shorts/"]) { display: none !important; }',
                'ytm-item-section-renderer:has(ytm-reel-shelf-renderer) { display: none !important; }',
                'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer) { display: none !important; }'
            ].join('\n');
            document.head.appendChild(style);

            // Function to auto-click skip button
            function trySkipAd() {
                // Desktop skip button
                var skipBtn = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
                if (skipBtn) {
                    skipBtn.click();
                    return true;
                }
                // Mobile skip button
                var mobileSkip = document.querySelector('.ytm-skip-ad-button, button[class*="skip"]');
                if (mobileSkip) {
                    mobileSkip.click();
                    return true;
                }
                return false;
            }

            // Function to speed through non-skippable ads
            function speedUpAd() {
                var player = document.querySelector('.html5-main-video, video');
                if (!player) return;

                var adShowing = document.querySelector('.ad-showing, .ad-interrupting');
                if (adShowing) {
                    // Try to skip first
                    if (!trySkipAd()) {
                        // Fast-forward non-skippable ads
                        if (player.duration && isFinite(player.duration) && player.duration > 0) {
                            player.currentTime = player.duration - 0.1;
                        }
                        player.playbackRate = 16;
                    }
                    // Mute during ads
                    if (!player.muted) {
                        player._adMuting = true;
                        player.muted = true;
                        setTimeout(function() { player._adMuting = false; }, 100);
                    }
                } else {
                    // Restore normal playback when not in ad
                    if (player.playbackRate > 2) {
                        player.playbackRate = 1;
                    }
                    // Restore mute state (fix: was never unmuted after ad ended)
                    if (player.muted && !player._userMuted) {
                        player._adMuting = true;
                        player.muted = false;
                        setTimeout(function() { player._adMuting = false; }, 100);
                    }
                }
            }

            // Function to stop inline playback (feed autoplay)
            function stopInlinePlayback() {
                var inlineVideos = document.querySelectorAll('ytm-inline-playback-renderer video');
                inlineVideos.forEach(function(video) {
                    if (!video.paused) {
                        try {
                            video.pause();
                        } catch (e) {}
                    }
                });
            }

            // Check for ads and inline playback periodically
            var adCheckInterval = setInterval(function() {
                trySkipAd();
                speedUpAd();
                stopInlinePlayback();
            }, 500);

            // Also use MutationObserver for faster detection
            var observer = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var m = mutations[i];
                    if (m.type === 'attributes' && m.attributeName === 'class') {
                        var target = m.target;
                        if (target.classList && (target.classList.contains('ad-showing') || target.classList.contains('ad-interrupting'))) {
                            setTimeout(function() {
                                trySkipAd();
                                speedUpAd();
                            }, 100);
                        }
                    }
                    if (m.type === 'childList' && m.addedNodes.length > 0) {
                        setTimeout(function() {
                            trySkipAd();
                            stopInlinePlayback();
                        }, 200);
                    }
                }
            });

            // Observe the player container
            function attachObserver() {
                var playerContainer = document.querySelector('#movie_player, .html5-video-player, #player-container-inner');
                if (playerContainer) {
                    observer.observe(playerContainer, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['class']
                    });
                }
                // Also observe body for feed ads
                if (document.body) {
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                }
            }

            // Attach observer when ready
            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                attachObserver();
            } else {
                document.addEventListener('DOMContentLoaded', attachObserver);
            }

            // Re-attach on navigation (YouTube is SPA)
            var lastUrl = location.href;
            setInterval(function() {
                if (location.href !== lastUrl) {
                    lastUrl = location.href;
                    setTimeout(attachObserver, 1000);
                }
            }, 1000);
        })();
    """.trimIndent()
}
