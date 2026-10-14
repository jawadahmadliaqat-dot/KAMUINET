/**
 * KamuiNet Ghost Signaling - Cloudflare Worker Proxy
 *
 * Deployment Instructions:
 * 1. Create a free Cloudflare Worker.
 * 2. Paste this code.
 * 3. Update SUPABASE_URL and SUPABASE_KEY in Cloudflare Environment Variables or hardcode them here.
 * 4. This Worker will act as a "Pardah" (Proxy) to hide your Supabase URL from ISP DPI.
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Replace this with your actual Supabase project URL
    const targetBase = "https://przvzvypctmywzvzyhge.supabase.co";

    // Construct the target URL
    const targetUrl = targetBase + url.pathname + url.search;

    // Clone headers and set security masks
    const newHeaders = new Headers(request.headers);
    newHeaders.set("Host", new URL(targetBase).host);

    // Illusion: Mask as a generic API or social CDN
    newHeaders.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

    const proxyRequest = new Request(targetUrl, {
      method: request.method,
      headers: newHeaders,
      body: request.body,
      redirect: 'manual'
    });

    return fetch(proxyRequest);
  },
};
