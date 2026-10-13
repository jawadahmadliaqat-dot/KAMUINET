-- =========================================================
-- 1. Active Hosts Table (Discovery ke liye)
-- =========================================================
CREATE TABLE IF NOT EXISTS public.active_hosts (
    uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    local_ip TEXT,
    socks_port INTEGER DEFAULT 1080,
    relay_host TEXT,
    relay_port INTEGER,
    mode TEXT DEFAULT 'local',       -- 'local' ya 'remote'
    status TEXT DEFAULT 'online',     -- 'online' ya 'offline'
    mb_limit INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (uid, device_id)
);

-- Realtime broadcast enable krna
ALTER PUBLICATION supabase_realtime ADD TABLE public.active_hosts;

-- Fast status filtering index
CREATE INDEX IF NOT EXISTS idx_active_hosts_status ON public.active_hosts (status);


-- =========================================================
-- 2. Signaling Table (P2P Handshake)
-- =========================================================
CREATE TABLE IF NOT EXISTS public.signaling (
    id BIGSERIAL PRIMARY KEY,
    target_uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    from_uid UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    target_device_id TEXT,
    from_device_id TEXT,
    kind TEXT NOT NULL,               -- 'sdp', 'ice', 'heartbeat'
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Realtime broadcast enable krna
ALTER PUBLICATION supabase_realtime ADD TABLE public.signaling;

-- Latency kam karne ke liye indexes (Target polling/filtering)
CREATE INDEX IF NOT EXISTS idx_signaling_target ON public.signaling (target_uid, target_device_id);
CREATE INDEX IF NOT EXISTS idx_signaling_created_at ON public.signaling (created_at);


-- =========================================================
-- 3. Row Level Security (RLS) Policies
-- =========================================================
ALTER TABLE public.active_hosts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.signaling ENABLE ROW LEVEL SECURITY;

-- Host Discovery: Sirf online hosts dikhein
CREATE POLICY "View online hosts" ON public.active_hosts
    FOR SELECT TO authenticated
    USING (status = 'online');

-- Self Host Management (Insert, Update, Delete)
CREATE POLICY "Manage own host record" ON public.active_hosts
    FOR ALL TO authenticated
    USING (auth.uid() = uid)
    WITH CHECK (auth.uid() = uid);

-- Signaling: Sirf Sender aur Receiver dekh saken
CREATE POLICY "Signaling visibility" ON public.signaling
    FOR SELECT TO authenticated
    USING (auth.uid() = target_uid OR auth.uid() = from_uid);

-- Signaling: Sirf Sender insert kar sakay
CREATE POLICY "Signaling insertion" ON public.signaling
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = from_uid);

-- Signaling: Consumed signals ko delete karne ki ijazat (Handshake ke baad)
CREATE POLICY "Signaling cleanup" ON public.signaling
    FOR DELETE TO authenticated
    USING (auth.uid() = target_uid OR auth.uid() = from_uid);


-- =========================================================
-- 4. Triggers & Maintenance Functions
-- =========================================================

-- Automatic updated_at trigger
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS update_active_hosts_updated_at ON public.active_hosts;
CREATE TRIGGER update_active_hosts_updated_at
    BEFORE UPDATE ON public.active_hosts
    FOR EACH ROW
    EXECUTE PROCEDURE update_updated_at_column();

-- Function: 5 minute se purani signaling rows delete krna
CREATE OR REPLACE FUNCTION purge_old_signals()
RETURNS void AS $$
BEGIN
    DELETE FROM public.signaling
    WHERE created_at < NOW() - INTERVAL '5 minutes';
END;
$$ LANGUAGE plpgsql;
