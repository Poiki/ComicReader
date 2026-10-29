package org.comicreader.app;

import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class WebDavTest {
    private static final URI ROOT = URI.create("https://example.org/comics/");

    private static byte[] xml(String responses) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:multistatus xmlns:d=\"DAV:\">"
                + responses + "</d:multistatus>").getBytes(StandardCharsets.UTF_8);
    }

    private static String response(String href, String properties) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + properties
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    @Test public void rootAndChildPreserveUnicodeAndLiteralUrlCharacters() throws Exception {
        WebDav dav = new WebDav("https://example.org/comics", "");
        assertEquals(ROOT, dav.root());
        URI child = dav.child(ROOT, "Niño + 100%.cbz");
        assertEquals("/comics/Niño + 100%.cbz", child.getPath());
        assertNull(child.getQuery());
        assertEquals("https://example.org/comics/Ni%C3%B1o%20+%20100%25.cbz", child.toASCIIString());
        assertEquals(URI.create("https://example.org/comics/states/"), dav.child(ROOT, "states/"));
    }

    @Test public void rejectsUnsafeEndpoints() {
        for (String value : new String[]{"http://example.org/comics", "https://u:p@example.org/comics",
                "https://example.org/comics?x=1", "https://example.org/comics#x", "https://example.org:0/comics",
                "https://example.org/comics/../outside/", "https://example.org/comics/%2e%2e/",
                "https://example.org/comics/%252E%252E/", "https://example.org/comics/a%2fb/",
                "https://example.org/comics/a%5cb/", "https://example.org/comics/%00/", "not a URI"}) {
            assertThrows(value, IOException.class, () -> new WebDav(value, ""));
        }
    }

    @Test public void rejectsHeaderInjection() {
        assertThrows(IOException.class, () -> new WebDav(ROOT.toString(), "Basic ok\r\nInjected: bad"));
        assertThrows(IOException.class, () -> new WebDav(ROOT.toString(), "Basic niño"));
    }

    @Test public void childCannotEscapeRoot() throws Exception {
        WebDav dav = new WebDav(ROOT.toString(), "");
        for (String name : new String[]{"", ".", "..", "../a", "a/b", "a\\b", "/a", "%2e%2e", "a\u0000b"})
            assertThrows(name, IOException.class, () -> dav.child(ROOT, name));
        assertThrows(IOException.class, () -> dav.child(URI.create("https://other.org/comics/"), "x"));
        assertThrows(IOException.class, () -> dav.child(URI.create("https://example.org/elsewhere/"), "x"));
    }

    @Test public void parsesNamespacedListingAndSkipsCollectionItself() throws Exception {
        List<WebDav.Entry> entries = WebDav.parseList(ROOT, xml(response("/comics/", "<d:resourcetype><d:collection/></d:resourcetype>")
                + response("/comics/Series%20A/", "<d:resourcetype><d:collection/></d:resourcetype>")
                + response("https://example.org:443/comics/Ni%C3%B1o%20%2B.cbz", "<d:resourcetype/><d:getcontentlength>123</d:getcontentlength>")));
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).directory());
        assertEquals("Series A", entries.get(0).name());
        assertEquals("Niño +.cbz", entries.get(1).name());
        assertFalse(entries.get(1).directory());
        assertEquals(123, entries.get(1).size());
    }

    @Test public void acceptsDefaultNamespaceRelativeHrefAndUnknownLength() throws Exception {
        String body = "<multistatus xmlns=\"DAV:\"><response><href>issue.cbz</href><propstat>"
                + "<prop><resourcetype/></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>";
        WebDav.Entry entry = WebDav.parseList(ROOT, body.getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals(ROOT.resolve("issue.cbz"), entry.uri());
        assertEquals(-1, entry.size());
        assertEquals(1, WebDav.parseList(ROOT, ("\ufeff" + body).getBytes(StandardCharsets.UTF_8)).size());
    }

    @Test public void failedOptionalPropertyDoesNotReplaceSuccessfulProperties() throws Exception {
        String body = "<d:response><d:href>/comics/one.cbz</d:href>"
                + "<d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                + "<d:propstat><d:prop><d:getcontentlength>bad</d:getcontentlength></d:prop>"
                + "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>";
        assertEquals(5, WebDav.parseList(ROOT, xml(body)).get(0).size());
    }

    @Test public void rejectsForeignOriginAndNonDirectChildren() {
        for (String href : new String[]{"https://other.org/comics/x.cbz", "http://example.org/comics/x.cbz",
                "https://example.org:444/comics/x.cbz", "/outside/x.cbz", "/comics/sub/x.cbz", "sub/../x.cbz",
                "/comics/%2e%2e/out.cbz", "/comics/%252e%252e/out.cbz", "/comics/a%2fb.cbz",
                "/comics/x.cbz?q=1", "/comics/x.cbz#frag"})
            assertThrows(href, IOException.class, () -> WebDav.parseList(ROOT, xml(response(href, "<d:resourcetype/>"))));
    }

    @Test public void rejectsMissingHrefFailedResourcesAndInvalidLengths() {
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml("<d:response/>")));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml("<d:response><d:href>/comics/a.cbz</d:href>"
                + "<d:status>HTTP/1.1 403 Forbidden</d:status></d:response>")));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml("<d:response><d:href>/comics/</d:href>"
                + "<d:status>HTTP/1.1 403 Forbidden</d:status></d:response>")));
        for (String size : new String[]{"-1", "+1", "no", "9223372036854775808"})
            assertThrows(IOException.class, () -> WebDav.parseList(ROOT,
                    xml(response("/comics/a.cbz", "<d:getcontentlength>" + size + "</d:getcontentlength>"))));
    }

    @Test public void rejectsDuplicateResources() {
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml(response("/comics/a.cbz", "<d:resourcetype/>")
                + response("/comics/%61.cbz", "<d:resourcetype/>"))));
    }

    @Test public void refusesDoctypeExternalEntitiesAndMalformedXml() {
        String attack = "<?xml version=\"1.0\"?><!DOCTYPE d:multistatus [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\">&x;</d:multistatus>";
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, attack.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, "<broken>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, new byte[]{(byte) 0xc0, (byte) 0xae}));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, attack.getBytes(StandardCharsets.UTF_16)));
    }

    @Test public void boundsResponseSizeEntryCountAndXmlDepth() {
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, new byte[2 * 1024 * 1024 + 1]));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 2001; i++) many.append(response("/comics/" + i + ".cbz", "<d:resourcetype/>"));
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml(many.toString())));
        String deep = "<x>".repeat(40) + "</x>".repeat(40);
        assertThrows(IOException.class, () -> WebDav.parseList(ROOT, xml(deep)));
    }
}
