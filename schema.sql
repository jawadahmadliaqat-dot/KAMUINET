-- 1. Active Hosts Table (Registration for Discovery)
-- UPDATED: Composite Primary Key (uid, device_id) to allow same-account multi-device
CREATE TABLE active_hosts (
    uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    local_ip TEXT,
    socks_port INTEGER DEFAULT 1080,
    relay_host TEXT,
    relay_port INTEGER,
    mode TEXT DEFAULT 'local', -- 'local' or 'remote'
    status TEXT DEFAULT 'online', -- 'online' or 'offline'
    mb_limit INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (uid, device_id)
);

-- Enable Realtime for active_hosts
ALTER PUBLICATION supabase_realtime ADD TABLE active_hosts;

-- 2. Signaling Table (P2P Handshake)
-- UPDATED: Added device_id columns for precise signal routing
CREATE TABLE signaling (
    id BIGSERIAL PRIMARY KEY,
    target_uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    from_uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    target_device_id TEXT,
    from_device_id TEXT,
    kind TEXT NOT NULL, -- 'sdp', 'ice', 'heartbeat'
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Enable Realtime for signaling
ALTER PUBLICATION supabase_realtime ADD TABLE signaling;

-- RLS Policies (Row Level Security)

-- Active Hosts: Anyone authenticated can view online hosts. Only owner can upsert.
ALTER TABLE active_hosts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "View online hosts" ON active_hosts
    FOR SELECT TO authenticated
    USING (status = 'online');

CREATE POLICY "Manage own host record" ON active_hosts
    FOR ALL TO authenticated
    USING (auth.uid() = uid);

-- Signaling: Only target or sender can see the signal.
ALTER TABLE signaling ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Signaling visibility" ON signaling
    FOR SELECT TO authenticated
    USING (auth.uid() = target_uid OR auth.uid() = from_uid);

CREATE POLICY "Signaling insertion" ON signaling
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = from_uid);

-- Automatic updated_at for active_hosts
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_active_hosts_updated_at
    BEFORE UPDATE ON active_hosts
    FOR EACH ROW
    EXECUTE PROCEDURE update_updated_at_column();
