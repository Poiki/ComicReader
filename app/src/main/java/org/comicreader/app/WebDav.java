package org.comicreader.app;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.SAXParserFactory;
import okhttp3.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** HTTPS WebDAV rooted at one explicitly configured collection. */
public final class WebDav {
    private static final int MAX_LIST_BYTES = 2 * 1024 * 1024, MAX_ENTRIES = 2000;
    private static final String DAV = "DAV:";
    private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
    private static final MediaType BINARY = MediaType.get("application/octet-stream");
    private static final byte[] PROPERTIES = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/>"
            + "</d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build();

    public record Entry(URI uri, String name, boolean directory, long size) {}
    private final URI root;
    private final String authorization;

    public WebDav(String endpoint, String authorization) throws IOException {
        if (endpoint == null) throw new IOException("Falta dirección WebDAV");
        try { root = folder(validate(new URI(endpoint))); }
        catch (URISyntaxException | IllegalArgumentException e) { throw new IOException("Dirección WebDAV inválida", e); }
        if (authorization == null || authorization.length() > 8192
                || authorization.chars().anyMatch(value -> value < 32 || value > 126))
            throw new IOException("Autorización WebDAV inválida");
        this.authorization = authorization;
    }

    public URI root() { return root; }

    public URI child(URI parent, String name) throws IOException {
        requireInside(parent);
        if (name == null) throw new IOException("Falta nombre WebDAV");
        String segment = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (segment.isEmpty() || segment.contains("/") || segment.contains("\\") || hasControl(segment))
            throw new IOException("Nombre WebDAV inválido");
        try {
            URI result = new URI(parent.getScheme(), parent.getRawAuthority(), folder(parent).getPath() + name, null, null);
            requireInside(result);
            return result;
        } catch (URISyntaxException e) { throw new IOException("Nombre WebDAV inválido", e); }
    }

    public List<Entry> list(URI directory) throws IOException {
        URI target = folder(requireInside(directory));
        Request request = request("PROPFIND", target, PROPERTIES).header("Depth", "1")
                .header("Accept", "application/xml").build();
        try (Response response = HTTP.newCall(request).execute()) {
            requireStatus(response, 207);
            return parseList(target, readLimited(response.body(), MAX_LIST_BYTES));
        }
    }

    public void mkdir(URI directory) throws IOException {
        try (Response response = HTTP.newCall(request("MKCOL", folder(requireInside(directory)), null).build()).execute()) {
            // RFC 4918: MKCOL returns 405 when the target already exists; listing verifies it next.
            requireStatus(response, 201, 405);
        }
    }

    public byte[] get(URI uri, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IOException("Límite de descarga inválido");
        try (Response response = HTTP.newCall(request("GET", uri, null).build()).execute()) {
            requireStatus(response, 200);
            return readLimited(response.body(), maxBytes);
        }
    }

    public void put(URI uri, byte[] bytes) throws IOException {
        try (Response response = HTTP.newCall(request("PUT", uri, bytes).build()).execute()) {
            requireStatus(response, 200, 201, 204);
        }
    }

    public InputStream download(URI uri) throws IOException {
        Call call = HTTP.newCall(request("GET", uri, null).build());
        Response response = call.execute();
        try {
            requireStatus(response, 200);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Respuesta WebDAV sin contenido");
            return new FilterInputStream(body.byteStream()) {
                @Override public int read() throws IOException {
                    try { return in.read(); } catch (IOException e) { throw release(e); }
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    try { return in.read(bytes, offset, count); } catch (IOException e) { throw release(e); }
                }
                private IOException release(IOException failure) {
                    try { close(); } catch (IOException e) { failure.addSuppressed(e); }
                    return failure;
                }
                @Override public void close() throws IOException {
                    call.cancel();
                    try { super.close(); } finally { response.close(); }
                }
            };
        } catch (IOException | RuntimeException e) {
            response.close();
            call.cancel();
            throw e;
        }
    }

    private Request.Builder request(String method, URI uri, byte[] bytes) throws IOException {
        requireInside(uri);
        RequestBody body = bytes == null ? null : RequestBody.create(bytes, method.equals("PROPFIND") ? XML : BINARY);
        Request.Builder request = new Request.Builder().url(uri.toURL()).method(method, body);
        if (!authorization.isEmpty()) request.header("Authorization", authorization);
        return request;
    }

    private static void requireStatus(Response response, int... accepted) throws IOException {
        for (int status : accepted) if (response.code() == status) return;
        throw new IOException("WebDAV " + response.request().method() + ": HTTP " + response.code());
    }

    private static byte[] readLimited(ResponseBody body, int maxBytes) throws IOException {
        if (body == null) throw new IOException("Respuesta WebDAV sin contenido");
        if (body.contentLength() > maxBytes) throw new IOException("Respuesta WebDAV demasiado grande");
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() > maxBytes - read) throw new IOException("Respuesta WebDAV demasiado grande");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private URI requireInside(URI uri) throws IOException {
        validate(uri);
        if (!sameOrigin(root, uri) || !(uri.getPath().equals(root.getPath()) || uri.getPath().startsWith(root.getPath())))
            throw new IOException("Recurso fuera de la carpeta WebDAV configurada");
        return uri;
    }

    private static URI validate(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IOException("WebDAV requiere HTTPS sin credenciales, consulta ni fragmento en la URL");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) return uri;
        validatePath(path);
        return uri;
    }

    private static void validatePath(String path) throws IOException {
        if (path.contains("//")) throw new IOException("Ruta WebDAV ambigua");
        for (String rawSegment : path.split("/", -1)) {
            String segment = rawSegment;
            for (int pass = 0; pass < 6; pass++) {
                if (segment.equals(".") || segment.equals("..") || segment.contains("/")
                        || segment.contains("\\") || hasControl(segment))
                    throw new IOException("Ruta WebDAV no segura");
                if (!segment.matches(".*%[0-9a-fA-F]{2}.*")) break;
                segment = decodeSegment(segment);
                if (pass == 5) throw new IOException("Ruta WebDAV con demasiadas codificaciones");
            }
        }
    }

    private static String decodeSegment(String raw) throws IOException {
        StringBuilder decoded = new StringBuilder();
        for (int offset = 0; offset < raw.length();) {
            if (raw.charAt(offset) != '%' || offset + 2 >= raw.length()
                    || Character.digit(raw.charAt(offset + 1), 16) < 0 || Character.digit(raw.charAt(offset + 2), 16) < 0) {
                decoded.append(raw.charAt(offset++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (offset + 2 < raw.length() && raw.charAt(offset) == '%'
                    && Character.digit(raw.charAt(offset + 1), 16) >= 0 && Character.digit(raw.charAt(offset + 2), 16) >= 0) {
                bytes.write(Character.digit(raw.charAt(offset + 1), 16) * 16 + Character.digit(raw.charAt(offset + 2), 16));
                offset += 3;
            }
            decoded.append(utf8(bytes.toByteArray()));
        }
        return decoded.toString();
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static URI folder(URI uri) {
        return uri.getPath().endsWith("/") ? uri : URI.create(uri.toASCIIString() + "/");
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort());
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Respuesta WebDAV no es UTF-8 válido", e); }
    }

    static List<Entry> parseList(URI directory, byte[] bytes) throws IOException {
        validate(directory);
        if (bytes.length > MAX_LIST_BYTES) throw new IOException("Listado WebDAV demasiado grande");
        String xml = utf8(bytes);
        // This gate also protects Android parsers without portable DOCTYPE-disabling features.
        if (xml.contains("<!DOCTYPE") || xml.indexOf('\0') >= 0) throw new IOException("XML WebDAV no seguro");
        if (xml.startsWith("\ufeff")) xml = xml.substring(1);
        Listing handler = new Listing(folder(directory));
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("Entidades externas prohibidas"); });
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.entries;
        } catch (Exception e) { throw new IOException("Listado WebDAV inválido", e); }
    }

    private static final class Listing extends DefaultHandler {
        private final URI directory;
        private final List<Entry> entries = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<String> elements = new ArrayList<>();
        private int responses, captureDepth;
        private boolean response, propstat, successfulProperties, isDirectory, propertyDirectory;
        private String href, status, propertyStatus, length, propertyLength, capture;
        private StringBuilder text;

        Listing(URI directory) { this.directory = directory; }

        @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException {
            elements.add(DAV.equals(uri) ? local : "");
            int depth = elements.size();
            if (depth > 32) throw new SAXException("XML demasiado profundo");
            if (depth == 1 && (!DAV.equals(uri) || !local.equals("multistatus")))
                throw new SAXException("Se esperaba DAV multistatus");
            if (text != null) throw new SAXException("Propiedad WebDAV con estructura inesperada");
            if (depth == 2 && DAV.equals(uri) && local.equals("response")) {
                if (++responses > MAX_ENTRIES + 1) throw new SAXException("Demasiados recursos WebDAV");
                response = true; successfulProperties = false; isDirectory = false;
                href = null; status = null; length = null;
            }
            if (!response || !DAV.equals(uri)) return;
            if (depth == 3 && local.equals("propstat")) {
                propstat = true; propertyDirectory = false; propertyLength = null; propertyStatus = null;
            }
            if (depth == 6 && local.equals("collection") && elements.get(4).equals("resourcetype")
                    && elements.get(3).equals("prop") && propstat) propertyDirectory = true;
            if (depth == 3 && (local.equals("href") || local.equals("status"))
                    || depth == 4 && local.equals("status") && propstat
                    || depth == 5 && local.equals("getcontentlength") && propstat && elements.get(3).equals("prop")) {
                capture = local; captureDepth = depth; text = new StringBuilder();
            }
        }

        @Override public void characters(char[] chars, int start, int count) throws SAXException {
            if (text != null) {
                if (text.length() + count > 8192) throw new SAXException("Propiedad WebDAV demasiado grande");
                text.append(chars, start, count);
            }
        }

        @Override public void endElement(String uri, String local, String qName) throws SAXException {
            int depth = elements.size();
            if (text != null && depth == captureDepth) {
                String value = text.toString().trim();
                if (capture.equals("href")) {
                    if (href != null) throw new SAXException("URI WebDAV duplicada");
                    href = value;
                } else if (capture.equals("getcontentlength")) propertyLength = value;
                else if (depth == 3) status = value;
                else propertyStatus = value;
                text = null;
            }
            if (response && DAV.equals(uri) && depth == 3 && local.equals("propstat")) {
                if (propertyStatus == null) throw new SAXException("Falta estado de propiedades WebDAV");
                if (success(propertyStatus)) {
                    successfulProperties = true; isDirectory |= propertyDirectory;
                    if (propertyLength != null) length = propertyLength;
                }
                propstat = false;
            }
            if (response && DAV.equals(uri) && depth == 2 && local.equals("response")) {
                try { addEntry(); } catch (IOException | IllegalArgumentException e) { throw new SAXException(e); }
                response = false;
            }
            elements.remove(depth - 1);
        }

        private void addEntry() throws IOException, SAXException {
            if (href == null || href.isEmpty()) throw new IOException("Falta URI WebDAV");
            URI link = URI.create(href);
            if (link.getRawPath() != null) validatePath(link.getRawPath());
            URI uri = directory.resolve(link);
            validate(uri);
            if (!sameOrigin(directory, uri)) throw new IOException("Listado WebDAV apunta a otro servidor");
            if ((status != null && !success(status)) || !successfulProperties) throw new IOException("Recurso WebDAV no accesible");
            String path = uri.getPath(), base = directory.getPath();
            if (path.equals(base) || (path + "/").equals(base)) return;
            if (!path.startsWith(base)) throw new IOException("Listado fuera de la carpeta WebDAV");
            String name = path.substring(base.length());
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty() || name.contains("/") || !seen.add(path)) throw new IOException("Listado WebDAV no es de hijos directos únicos");
            long size = -1;
            if (length != null) {
                if (!length.matches("[0-9]+")) throw new IOException("Tamaño WebDAV inválido");
                try { size = Long.parseLong(length); } catch (NumberFormatException e) { throw new IOException("Tamaño WebDAV inválido", e); }
            }
            if (entries.size() == MAX_ENTRIES) throw new IOException("Demasiados recursos WebDAV");
            entries.add(new Entry(isDirectory ? folder(uri) : uri, name, isDirectory, isDirectory ? 0 : size));
        }

        private static boolean success(String status) throws SAXException {
            if (!status.matches("HTTP/[^ ]+ [1-5][0-9]{2}( .*)?")) throw new SAXException("Estado HTTP WebDAV inválido");
            int code = Integer.parseInt(status.split(" ", 3)[1]);
            return code >= 200 && code < 300;
        }

        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
    }
}
