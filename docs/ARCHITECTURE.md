# ARCHITECTURE: KamuiNet

This file provides a deeper, developer-focused architecture document that references repository artifacts and traces the runtime flows.

## High-level components

- Android Client (Kotlin): responsible for auth, presence, signaling, WebRTC negotiation, and optional VPN routing via `KamuiVpnService`.
- Supabase (Auth + Postgres + Realtime): holds `active_hosts` and `signaling` tables and enforces RLS policies.
- Cloudflare Worker: optional proxy to mask Supabase endpoint and act as an edge relay.

## Data flows & sequence

1. User signs in on Android client (Supabase Auth).
2. Client inserts/updates a row in `public.active_hosts` with device metadata.
3. Client listens to Realtime events or polls `public.signaling` for incoming handshake payloads.
4. Initiator writes an SDP/ICE payload into `public.signaling` with `target_uid` and `target_device_id`.
5. Target reads payloads where `target_uid` matches; responds with its own payload.
6. WebRTC negotiation completes; DataChannel established.
7. Optional: KamuiVpnService creates a local interface or SOCKS proxy that forwards traffic through the DataChannel to peer device.

## Important files (evidence)

- `schema.sql` — DB schema, RLS policies, purge function, triggers
- `cloudflare_worker.js` — proxy implementation
- `libs.versions.toml` — list of used Android libraries and versions

## Notable design decisions & rationale

- Using Supabase Realtime reduces build complexity vs building a custom signaling server.
- RLS policies provide per-user isolation to prevent cross-user signaling snooping.
- Purging signals after 5 minutes prevents DB growth but may require retry logic on slow mobile networks.
- Cloudflare Worker proxy is used to mask the Supabase endpoint and provide a generic looking Host header. This helps in cases of DPI by ISPs.

## Gaps & recommended improvements

- Add TURN server fallback for reliable connections where direct P2P fails.
- Add incremental backoff and retries when clients fail to complete handshake before signal purge.
- Implement logging & observability for failed handshake reasons (auth, STUN/TURN, ICE timeout).

