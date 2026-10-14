# DEPLOYMENT: Supabase & Cloudflare Worker

This document provides step-by-step deployment instructions for the infrastructure pieces included in the repo.

## Supabase (Postgres + Realtime)

1. Create a new Supabase project.
2. From the Supabase dashboard, go to SQL Editor and run the contents of `schema.sql`.
3. Confirm RLS policies are enabled for `active_hosts` and `signaling`.
4. In the "Settings -> API" or the Realtime UI, ensure publications for `supabase_realtime` includes the two tables.
5. Create service role key for server tasks and store it in a secure secret store (DO NOT embed in client builds).

### Quick psql deploy (example)

```bash
psql "postgresql://<user>:<pass>@<host>:5432/<db>" -f schema.sql
```

## Cloudflare Worker (optional)

1. Install Wrangler: https://developers.cloudflare.com/workers/get-started/guide/
2. Login to Cloudflare and create a worker.
3. Create environment secrets for SUPABASE_URL and SUPABASE_KEY in Cloudflare Dashboard or via `wrangler secret`.

Example wrangler.toml (minimal):

```toml
name = "kamui-worker"
main = "cloudflare_worker.js"
compatibility_date = "2026-01-01"

[env.production]
route = "kamui.example.com/*"
zone_id = "<ZONE_ID>"

```

Deploy:

```bash
wrangler publish cloudflare_worker.js --name kamui-worker
```

## Notes & security

- Use Cloudflare secrets (environment variables) rather than hardcoding the Supabase URL/keys in the worker source.
- Protect the worker with rate-limiting if it will be publicly addressable.

