# Fix: ICE Candidate Buffering & Connection Reset Logic

Address the "ICE-before-offer" race condition and "Stale target overwrite" bug in `KamuiHostService` to ensure handshake reliability and clean session management.

## User Review Required

> [!IMPORTANT]
> - **ICE Buffering**: The Host will now cache ICE candidates arriving *before* the SDP offer. This is critical as Supabase broadcast order is not guaranteed.
> - **Connection Reset**: If a new offer is received from a different device (or a retry from the same device), the existing PeerConnection will be closed and re-initialized to prevent state corruption.

## Proposed Changes

### [1. Host Side Signaling Reliability]

#### [MODIFY] [KamuiHostService.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/router/KamuiHostService.kt)
- **Candidate Buffer**: Add a `ConcurrentHashMap<String, MutableList<IceCandidateWrapper>>` using `Collections.synchronizedList(mutableListOf())` to store early ICE candidates keyed by `fromDeviceId`.
- **`onIce` Handler**:
    - Buffer candidates if `targetDeviceId` is not yet set.
    - Forward directly to `WebRtcManager` if the sender matches the current `targetDeviceId`.
- **`onSdp` (Offer) Handler**:
    - If `targetDeviceId` is already set, call `hostRtcManager.close()` and re-initialize it to handle the new session cleanly.
    - Set `targetUid` and `targetDeviceId`.
    - **Drain Buffer**: Immediately push all cached candidates for this `fromDeviceId` to the `WebRtcManager`.
- **State Reset**: In `onChannelStateChanged`, reset `targetUid` and `targetDeviceId` to `null` if the state transitions to `CLOSED` or `CLOSING`.

### [2. WebRTC Manager Lifecycle]

#### [MODIFY] [WebRtcManager.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/webrtc/WebRtcManager.kt)
- Ensure `close()` is idempotent and safe to call multiple times. (It already seems mostly safe, but will verify).

## Verification Plan

### Automated Tests
- None. This is a timing-sensitive signaling fix.

### Manual Verification
1. **Race Condition Test**: Monitor Logcat. If `[HANDSHAKE] Buffering early ICE` appears, verify that they are eventually drained with `Draining X buffered ICE candidates` once the offer arrives.
2. **Re-connection Test**: While a tunnel is "Waiting for P2P," restart the Client. Verify the Host logs `Closing existing peer connection for new offer` and successfully handshakes with the new attempt.
3. **Reset Check**: Disconnect the tunnel. Verify that subsequent connection attempts start with a null `targetDeviceId`.
