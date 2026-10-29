package org.comicreader.app;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

record ReleaseInfo(String version, String tag, String asset, String url, long size, String sha256) {
    static final String REPOSITORY = "https://github.com/Poiki/ComicReader";
    static final String API = "https://api.github.com/repos/Poiki/ComicReader/releases/latest";
    static final long MAX_APK = 200L * 1024 * 1024;
    static final int MAX_JSON = 4 * 1024 * 1024;
    static int compareVersions(String left, String right) throws IOException { return Long.compare(number(left), number(right)); }
    private static long number(String value) throws IOException {
        if (value == null || !value.matches("v?(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})"))
            throw new IOException("Versión de release no válida");
        String[] parts = value.replaceFirst("^v", "").split("\\.");
        return Long.parseLong(parts[0]) * 100_000_000L + Long.parseLong(parts[1]) * 10_000L + Long.parseLong(parts[2]);
    }
    static boolean trustedUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com").contains(uri.getHost());
        } catch (Exception invalid) { return false; }
    }
    static ReleaseInfo parse(String json, String current, String[] abis) throws IOException {
        if (json.length() > MAX_JSON) throw new IOException("La release supera el límite de metadatos");
        try {
            JSONObject release = new JSONObject(json);
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null;
            String tag = release.getString("tag_name");
            if (compareVersions(tag, current) <= 0) return null;
            JSONArray assets = release.getJSONArray("assets");
            if (assets.length() > 100) throw new IOException("Demasiados archivos en la release");
            Map<String, JSONObject> byName = new HashMap<>();
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (byName.put(asset.getString("name"), asset) != null) throw new IOException("La release contiene nombres duplicados");
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (String abi : abis) if (Set.of("arm64-v8a", "x86_64").contains(abi)) wanted.add("ComicReader-" + abi + ".apk");
            wanted.add("ComicReader-universal.apk");
            for (String name : wanted) {
                JSONObject asset = byName.get(name); if (asset == null) continue;
                String url = asset.getString("browser_download_url"), digest = asset.getString("digest");
                Object bytes = asset.get("size"); long size = bytes instanceof Number number ? number.longValue() : -1;
                if (!"uploaded".equals(asset.getString("state")) || size < 1 || size > MAX_APK || ((Number)bytes).doubleValue() != size
                        || !digest.matches("sha256:[0-9a-f]{64}") || !trustedUrl(url)
                        || !url.equals(REPOSITORY + "/releases/download/" + tag + "/" + name))
                    throw new IOException("APK de la release sin tamaño, origen o SHA-256 válidos");
                return new ReleaseInfo(tag.replaceFirst("^v", ""), tag, name, url, size, digest.substring(7));
            }
            throw new IOException("La release no incluye un APK compatible con tu dispositivo");
        } catch (JSONException | ClassCastException error) { throw new IOException("Metadatos de release inválidos", error); }
    }
}
