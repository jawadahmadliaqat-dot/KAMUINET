# KamuiNet

Privacy-first P2P tunneling for Android devices using WebRTC for direct peer-to-peer data channels and Supabase/Postgres for discovery and signaling.

Badges: WebRTC · Android · Kotlin · Supabase · Cloudflare

## Quick summary

KamuiNet enables encrypted, low-latency direct connectivity between Android devices. Devices discover each other using a small Supabase-backed signaling layer (Postgres + RLS) and establish WebRTC data channels for P2P transport. A Cloudflare Worker is provided as an optional proxy for the Supabase endpoint.

## Highlights (extracted from repository files)

- Database schema and operational logic: schema.sql
  - Key table: public.active_hosts (uid, device_id, local_ip, socks_port=1080, relay_host, relay_port, mode, status, mb_limit, created_at, updated_at)
  - Signals table with automatic purging: purge_old_signals() deletes signaling rows older than 5 minutes.
  - Trigger: update_updated_at_column() to keep updated_at in sync.

- Cloudflare Worker: cloudflare_worker.js
  - Proxy pattern: rewrites Host header and forwards requests to the configured Supabase base URL. Example excerpt:

    const targetBase = "https://przvzvypctmywzvzyhge.supabase.co";
    newHeaders.set("Host", new URL(targetBase).host);

  - Important: do NOT embed Supabase service-role keys in the Worker source. Use Wrangler/environment secrets.

- Android app build: Gradle and dependency catalog (gradle/libs.versions.toml)
  - Android Gradle Plugin: 9.3.2
  - Notable libraries: Hilt (DI), Room (persistence), stream-webrtc-android (WebRTC data channels), Camera/MLKit/Zxing for QR pairing

## What changed in this README

- Collated architecture notes and evidence (schema, worker, dependency catalog) into concise, actionable sections.
- Added a Quick Start with exact commands for database, worker, and Android build.
- Added explicit security guidance about secrets, RLS, and signal retention.
- Added a short developer checklist and recommended next steps (CI, LICENSE, TURN fallback).

## Quick start (developer)

1) Database (Supabase)

- Apply schema (run in Supabase SQL Editor or via psql):

  psql "postgresql://<user>:<pass>@<host>:5432/<db>" -f schema.sql

2) Cloudflare Worker (local dev & deploy)

- Run locally with Wrangler:

  wrangler dev cloudflare_worker.js

- Publish (replace name and configure secrets in Wrangler):

  wrangler publish cloudflare_worker.js --name kamui-worker

3) Android app (once app/ sources present)

- Build:

  ./gradlew assembleDebug

- Install onto device/emulator:

  ./gradlew installDebug


## Security & operational notes

- Secrets: remove any service-role keys or client-insecure keys from the repo. Use environment secrets for Wrangler and server-side services.
- RLS: schema.sql enforces Row Level Security on signaling and active_hosts. Audit policies when changing schema or auth rules.
- Signal retention: currently signals are purged after 5 minutes — consider increasing retention or implementing retries for extremely lossy mobile networks.
- TURN fallback: WebRTC P2P will fail in some NAT scenarios. Plan a TURN server or managed fallback for reliable connectivity.

## Developer checklist (recommended next commits)

- [ ] Add LICENSE (MIT or Apache-2.0)
- [ ] Add GitHub Actions: build, lint, unit tests for Android module
- [ ] Add docs/ARCHITECTURE.md and docs/DEPLOY.md with longer instructions
- [ ] Verify cloudflare_worker.js contains no embedded secrets and uses Wrangler secrets
- [ ] Add integration tests around signaling lifecycle and DB migrations
- [ ] Consider adding TURN server configuration and instructions

## Contributing

- Please open issues for bugs and feature requests. Pull requests should target `main` with a description of changes and associated tests when applicable.

## Where to look next (files I used)

- README.md (this file)
- schema.sql — database schema, triggers, RLS policies
- cloudflare_worker.js — worker proxy used to forward to Supabase
- gradle/libs.versions.toml — dependency catalog and key libraries
- app/build.gradle.kts and app/src/ — Android module configuration and source tree

---

If you'd like, I can now:
- Expand docs/ARCHITECTURE.md using extracted mermaid diagrams and code excerpts from the repository
- Produce a GitHub Actions workflow that runs ./gradlew assembleDebug and basic lint checks
- Open a PR with these README improvements

Commit: chore(docs): improve README using data from schema.sql, cloudflare_worker.js, and gradle catalog
