# P2P Architecture Refinement & Connectivity Fix

This plan refines the VPN lifecycle and signaling logic based on the identified architectural weaknesses and signaling room mismatches.

## User Review Required

> [!IMPORTANT]
> **Delayed VPN Activation:** I am moving `establishVpn()` to be triggered ONLY when the `DataChannel` state becomes `OPEN`. This prevents the "device blackout" issue where all other apps lose internet during a potentially long P2P handshake.

> [!NOTE]
> **Signaling Channel Lock:** All P2P signaling (Offer, Answer, ICE) will now strictly stay within the **Host's room** (`p2p-signals-<HostUID>`). The Host will no longer attempt to broadcast to the Client's room.

## Proposed Changes

### [VPN Service Lifecycle]
#### [MODIFY] [KamuiVpnService.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/vpn/KamuiVpnService.kt)
* Remove `establishVpn()` from the `onStartCommand` flow.
* Trigger `establishVpn()` inside the `onChannelStateChanged` callback when state is `DataChannel.State.OPEN`.
* Ensure `setupRtcCallbacks` remains the primary listener on the Host's room.
* Update `onSdpGenerated` to always broadcast to `targetUid` (Host) even for ICE candidates.

### [Host Signaling]
#### [MODIFY] [KamuiHostService.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/router/KamuiHostService.kt)
* Update `onSdpGenerated` and `onIceCandidateGenerated` to use `currentUserId` (Self/Host) as the `targetUid` for broadcasts. This keeps the conversation in the same room the Client is listening to.

### [Networking Optimization]
#### [MODIFY] [PacketRouter.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/router/PacketRouter.kt)
* **MSS Sync:** Change MSS value from 1360 to **1240** to match the new 1280 MTU.

#### [MODIFY] [HostRelayManager.kt](file:///C:/Users/hp/AndroidStudioProjects/KamuiNet/app/src/main/java/com/kamuinet/client/router/HostRelayManager.kt)
* **Socket Protection:** Add `protect()` calls to TCP and UDP sockets to ensure the Host's own potential VPN doesn't cause a loop.

---

## Verification Plan

### Automated Tests
* Build the project to verify API compatibility.

### Manual Verification
* **Device Blackout Test:** Verify that other apps (e.g., Browser) still have internet while KamuiNet is in "Establishing P2P Tunnel..." state.
* **Handshake Speed:** Confirm the `DataChannel` opens quickly now that rooms are aligned.
* **Traffic Flow:** Confirm the VPN "Key" icon appears ONLY after the tunnel is active.
