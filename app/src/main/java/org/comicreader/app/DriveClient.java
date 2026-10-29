package org.comicreader.app;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Direct Drive v3 access. All operations run on the caller's background executor. */
public final class DriveClient {
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final int MAX_LIST_BYTES = 2 * 1024 * 1024, MAX_ENTRIES = 10_000;
    private static final long MAX_SYNC_BYTES = 64L * 1024 * 1024;
    private static final Object SYNC = new Object();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType BINARY = MediaType.get("application/octet-stream");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build();

    public record Entry(String id, String name, boolean directory, long size) { }
    private record Page(List<Entry> entries, String next) { }
    private final String authorization;
    private final OkHttpClient http;

    public DriveClient(String bearer) throws IOException { this(bearer, HTTP); }

    DriveClient(String bearer, OkHttpClient http) throws IOException {
        requireToken(bearer);
        this.authorization = "Bearer " + bearer;
        this.http = Objects.requireNonNull(http);
    }

    static void requireToken(String token) throws IOException {
        if (token == null || token.isEmpty() || token.length() > 16384)
            throw new IOException("Google no devolvió un token de acceso válido; vuelve a conectar");
        for (int i = 0; i < token.length(); i++)
            if (token.charAt(i) <= 32 || token.charAt(i) >= 127)
                throw new IOException("Token de acceso de Google inválido");
    }

    public List<Entry> list(String folderId) throws IOException {
        String folder = folderId == null || folderId.isEmpty() ? "root" : requireId(folderId);
        List<Entry> entries = listFiles("drive", "'" + folder + "' in parents and trashed = false", MAX_ENTRIES);
        entries.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : ComicFiles.naturalCompare(a.name(), b.name()));
        return entries;
    }

    void revoke() throws IOException {
        RequestBody body = new FormBody.Builder().add("token", authorization.substring("Bearer ".length())).build();
        Request request = new Request.Builder().url("https://oauth2.googleapis.com/revoke").post(body).build();
        try (Response response = http.newCall(request).execute()) { requireStatus(response, 200); }
    }

    public InputStream download(String id) throws IOException {
        HttpUrl url = HttpUrl.get(API).newBuilder().addPathSegment(requireId(id)).addQueryParameter("alt", "media").build();
        Call call = http.newCall(request(url).get().build());
        Response response = call.execute();
        try {
            requireStatus(response, 200);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Drive devolvió una descarga vacía");
            if (body.contentLength() > ComicFiles.MAX_COMIC) throw new IOException("El cómic de Drive supera 1 GiB");
            return new FilterInputStream(body.byteStream()) {
                @Override public void close() throws IOException {
                    call.cancel();
                    try { super.close(); } finally { response.close(); }
                }
            };
        } catch (IOException | RuntimeException e) {
            response.close(); call.cancel(); throw e;
        }
    }

    public int sync(Context context) throws IOException {
        synchronized (SYNC) {
            Library library = Library.get(context);
            List<Entry> files = listFiles("appDataFolder", "trashed = false", 512);
            Map<String, Progress> remote = new HashMap<>();
            Set<String> names = new HashSet<>();
            String ownId = null; int devices = 0; long transferred = 0;
            for (Entry entry : files) {
                if (entry.directory() || !entry.name().matches("[0-9a-f-]{36}\\.crp")) continue;
                if (!names.add(entry.name())) throw new IOException("Drive contiene instantáneas de dispositivo duplicadas");
                if (++devices > 128) throw new IOException("Drive contiene más de 128 dispositivos; archiva los antiguos");
                if (entry.name().equals(library.device + ".crp")) ownId = entry.id();
                byte[] bytes;
                try (InputStream input = download(entry.id())) { bytes = ComicFiles.read(input, Progress.MAX_BYTES); }
                transferred += bytes.length;
                if (transferred > MAX_SYNC_BYTES) throw new IOException("Las instantáneas de Drive superan 64 MiB por sincronización");
                merge(remote, Progress.decode(bytes));
            }
            byte[] snapshot;
            synchronized (library) {
                merge(remote, library.states());
                snapshot = Progress.encode(remote.values());
                library.merge(remote.values());
            }
            writeSnapshot(ownId, library.device + ".crp", snapshot);
            return remote.size();
        }
    }

    private static void merge(Map<String, Progress> target, Collection<Progress> states) throws IOException {
        try {
            for (Progress state : states) {
                Progress old = target.get(state.id);
                target.put(state.id, old == null ? state : Progress.latest(old, state));
                if (target.size() > Progress.MAX_BOOKS) throw new IOException("Demasiados cómics en el progreso de Drive");
            }
        } catch (IllegalArgumentException e) { throw new IOException("Drive contiene revisiones de progreso contradictorias", e); }
    }

    private List<Entry> listFiles(String space, String query, int limit) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>(), tokens = new HashSet<>();
        String token = ""; int pages = 0;
        do {
            if (++pages > 128) throw new IOException("Demasiadas páginas de listado en Drive");
            HttpUrl.Builder url = HttpUrl.get(API).newBuilder().addQueryParameter("spaces", space)
                    .addQueryParameter("q", query).addQueryParameter("pageSize", "1000")
                    .addQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name,mimeType,size)");
            if (!token.isEmpty()) url.addQueryParameter("pageToken", token);
            Page page;
            try (Response response = http.newCall(request(url.build()).get().build()).execute()) {
                requireStatus(response, 200);
                page = parsePage(read(response.body(), MAX_LIST_BYTES));
            }
            for (Entry entry : page.entries()) {
                if (!ids.add(entry.id())) throw new IOException("Drive repitió un identificador durante el listado; vuelve a intentarlo");
                if (entries.size() == limit) throw new IOException("Demasiados archivos en la carpeta de Drive");
                entries.add(entry);
            }
            token = page.next();
            if (!token.isEmpty() && !tokens.add(token)) throw new IOException("Drive repitió el cursor del listado");
        } while (!token.isEmpty());
        return entries;
    }

    private void writeSnapshot(String id, String name, byte[] snapshot) throws IOException {
        HttpUrl.Builder url = HttpUrl.get(UPLOAD).newBuilder();
        RequestBody body; String method;
        if (id != null) {
            url.addPathSegment(requireId(id)).addQueryParameter("uploadType", "media");
            body = RequestBody.create(snapshot, BINARY); method = "PATCH";
        } else {
            url.addQueryParameter("uploadType", "multipart");
            try {
                JSONObject metadata = new JSONObject().put("name", name).put("parents", new JSONArray().put("appDataFolder"));
                body = new MultipartBody.Builder().setType(MediaType.get("multipart/related"))
                        .addPart(RequestBody.create(metadata.toString(), JSON)).addPart(RequestBody.create(snapshot, BINARY)).build();
            } catch (JSONException e) { throw new IOException("No se pudo preparar el progreso de Drive", e); }
            method = "POST";
        }
        url.addQueryParameter("fields", "id");
        try (Response response = http.newCall(request(url.build()).method(method, body).build()).execute()) {
            requireStatus(response, 200, 201);
        }
    }

    private Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("Authorization", authorization);
    }

    private static String requireId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}")) throw new IOException("Identificador de Drive inválido");
        return id;
    }

    private static void requireStatus(Response response, int... accepted) throws IOException {
        for (int status : accepted) if (response.code() == status) return;
        String action = response.code() == 401 ? "; vuelve a conectar tu cuenta"
                : response.code() == 403 ? "; revisa los permisos, la API de Drive y la cuota"
                : response.code() == 429 ? "; espera antes de volver a intentarlo" : "";
        throw new IOException("Google Drive: HTTP " + response.code() + action);
    }

    private static byte[] read(ResponseBody body, int limit) throws IOException {
        if (body == null) throw new IOException("Respuesta de Drive sin contenido");
        if (body.contentLength() > limit) throw new IOException("Respuesta de Drive demasiado grande");
        try (InputStream input = body.byteStream()) { return ComicFiles.read(input, limit); }
    }

    private static Page parsePage(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONObject json = new JSONObject(text);
            if (json.optBoolean("incompleteSearch", false)) throw new IOException("Drive devolvió una búsqueda incompleta");
            JSONArray files = json.getJSONArray("files");
            if (files.length() > 1000) throw new IOException("Página de Drive demasiado grande");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String id = requireId(file.getString("id")), name = file.getString("name");
                if (name.isEmpty() || name.length() > 4096) throw new IOException("Nombre de archivo de Drive inválido");
                boolean directory = file.getString("mimeType").equals(FOLDER);
                long size = file.has("size") ? file.getLong("size") : -1;
                if (size < -1) throw new IOException("Tamaño de archivo de Drive inválido");
                entries.add(new Entry(id, name, directory, directory ? 0 : size));
            }
            String next = json.optString("nextPageToken", "");
            if (next.length() > 8192) throw new IOException("Cursor de Drive demasiado grande");
            return new Page(entries, next);
        } catch (JSONException | CharacterCodingException e) { throw new IOException("Listado de Drive inválido", e); }
    }
}
