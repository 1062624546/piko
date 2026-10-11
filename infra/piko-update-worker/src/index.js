// Update metadata and APK download proxy for a private GitHub Release.
// GITHUB_TOKEN is a Cloudflare Worker secret, NEVER embedded into the Android APK.
const owner = (env) => env.GITHUB_OWNER || "1062624546";
const repo = (env) => env.GITHUB_REPOSITORY || "piko";
const apiBase = (env) => "https://api.github.com/repos/" + encodeURIComponent(owner(env)) + "/" + encodeURIComponent(repo(env));
const headers = (env, accept) => ({
  Authorization: "Bearer " + env.GITHUB_TOKEN,
  Accept: accept || "application/vnd.github+json",
  "User-Agent": "Piko-Update-Worker",
  "X-GitHub-Api-Version": "2022-11-28",
});
const failure = (reason, status) => new Response(
  JSON.stringify({error: reason}), {
    status: status || 502,
    headers: {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"},
  }
);
async function currentRelease(env) {
  const response = await fetch(apiBase(env) + "/releases/latest", {
    headers: headers(env),
    redirect: "manual",
  });
  if (!response.ok) throw new Error("GitHub release unavailable");
  const release = await response.json();
  if (release.draft || release.prerelease) throw new Error("No stable release");
  return release;
}
export default {
  async fetch(request, env) {
    if (!env.GITHUB_TOKEN) return failure("Update service not configured", 503);
    if (request.method !== "GET" && request.method !== "HEAD") return failure("Method not allowed", 405);
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    try {
      if (path === "/latest") {
        const release = await currentRelease(env);
        const assets = (release.assets || [])
          .filter((a) => a.name.toLowerCase().endsWith(".apk"))
          .map((a) => ({
            name: a.name,
            size: a.size,
            digest: a.digest,
            browser_download_url: url.origin + "/assets/" + a.id,
          }));
        return new Response(JSON.stringify({
          tag_name: release.tag_name,
          body: release.body || "",
          html_url: url.origin,
          draft: false,
          prerelease: false,
          assets,
        }), {
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "public, max-age=120",
          },
        });
      }
      const match = /^\/assets\/(\d+)$/.exec(path);
      if (!match) return failure("Not found", 404);
      const release = await currentRelease(env);
      const asset = (release.assets || []).find(
        (a) => String(a.id) === match[1] && a.name.toLowerCase().endsWith(".apk"),
      );
      if (!asset) return failure("APK not found", 404);
      const range = request.headers.get("Range");
      const upstream = await fetch(apiBase(env) + "/releases/assets/" + asset.id, {
        method: request.method,
        headers: {...headers(env, "application/octet-stream"), ...(range ? {Range: range} : {})},
        redirect: "manual",
      });
      let response = upstream;
      if ([301, 302, 303, 307, 308].includes(upstream.status)) {
        const location = upstream.headers.get("Location");
        if (!location || !location.startsWith("https://")) return failure("Bad artifact redirect");
        // Signed CDN requests must NOT carry our GitHub Authorization token.
        response = await fetch(location, {
          method: request.method,
          headers: range ? {Range: range} : {},
        });
      }
      if (!response.ok) return failure("APK unavailable");
      if ((response.headers.get("Content-Type") || "").includes("application/json"))
        return failure("GitHub returned metadata instead of APK");
      const h = new Headers();
      for (const key of ["Content-Length", "Content-Range", "Accept-Ranges"])
        if (response.headers.get(key)) h.set(key, response.headers.get(key));
      h.set("Content-Type", "application/vnd.android.package-archive");
      h.set("Content-Disposition", 'attachment; filename="' + asset.name.replace(/[^a-zA-Z0-9._-]/g, "_") + '"');
      h.set("Cache-Control", "no-store");
      return new Response(request.method === "HEAD" ? null : response.body, {
        status: response.status,
        headers: h,
      });
    } catch (e) {
      return failure("Update service temporarily unavailable");
    }
  },
};
