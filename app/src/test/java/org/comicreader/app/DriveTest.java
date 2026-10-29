package org.comicreader.app;

import java.io.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DriveTest {
    @Test public void partialConsentAndHeaderInjectionAreRejected() throws Exception {
        assertEquals("token", DriveAuth.requireAuthorization("token", List.of(DriveAuth.READ, DriveAuth.STATE)));
        assertThrows(IOException.class, () -> DriveAuth.requireAuthorization("token", List.of(DriveAuth.READ)));
        assertThrows(IOException.class, () -> new DriveClient("token\r\nInjected: x"));
        assertThrows(IOException.class, () -> new DriveClient(""));
    }

    @Test public void downloadUsesApiIdAndBearerHeaderAndClosesBody() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        byte[] fixture = {1, 2, 3};
        ResponseBody source = ResponseBody.create(fixture, MediaType.get("application/octet-stream"));
        okio.BufferedSource trackedSource = okio.Okio.buffer(new okio.ForwardingSource(source.source()) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        });
        ResponseBody tracked = new ResponseBody() {
            @Override public MediaType contentType() { return source.contentType(); }
            @Override public long contentLength() { return source.contentLength(); }
            @Override public okio.BufferedSource source() { return trackedSource; }
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            assertEquals("https", request.url().scheme());
            assertEquals("www.googleapis.com", request.url().host());
            assertEquals("/drive/v3/files/abc_123", request.url().encodedPath());
            assertEquals("media", request.url().queryParameter("alt"));
            assertEquals("Bearer token", request.header("Authorization"));
            assertNull(request.url().queryParameter("access_token"));
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(tracked).build();
        }).build();
        try (InputStream input = new DriveClient("token", http).download("abc_123")) {
            assertArrayEquals(fixture, ComicFiles.read(input, 3));
        }
        assertTrue(closed.get());
    }

    @Test public void invalidIdAndAuthenticationFailureAreExplicit() throws Exception {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401)
                        .message("Unauthorized").body(ResponseBody.create(new byte[0], null)).build()).build();
        DriveClient client = new DriveClient("token", http);
        assertThrows(IOException.class, () -> client.download("../../other"));
        IOException failure = assertThrows(IOException.class, () -> client.download("abc"));
        assertTrue(failure.getMessage().contains("401"));
        assertFalse(failure.getMessage().contains("token"));
    }

    @Test public void revokePostsTokenInBodyToGoogleOnly() throws Exception {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            assertEquals("https://oauth2.googleapis.com/revoke", request.url().toString());
            assertNull(request.header("Authorization"));
            assertEquals("POST", request.method());
            FormBody body = (FormBody) request.body();
            assertEquals("token", body.name(0)); assertEquals("secret-token", body.value(0));
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(new byte[0], null)).build();
        }).build();
        new DriveClient("secret-token", http).revoke();
    }
}
