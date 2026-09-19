# Tasks: Fix RTC Callback Initialization & Handshake

- `[x]` **Phase 1: RTC Initialization Fix**
    - `[x]` Swap order in `KamuiVpnService.kt`: Initialize `rtcManager` *before* calling `setupRtcCallbacks`.
- `[ ]` **Phase 2: Verification**
    - `[ ]` Monitor Logcat for `[BROADCAST_SENT]` on Client.
    - `[ ]` Monitor Logcat for `🔥 OFFER MIL GAYI!` on Host.
