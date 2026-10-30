package org.comicreader.app;

import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses Android's real JSON parser and intercepted HTTP; no account or cloud is contacted. */
public final class DriveClientInstrumentedTest {
    public static void run() throws Exception {
        testListsAllPagesAndOrdersFoldersAndNaturalNumbers();
        testRepeatedCursorDoesNotLoopAndMalformedListingsFail();
        testDuplicateFileIdsAreNotSilentlyAccepted();
    }

    private static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("Drive check failed");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void assertNull(Object actual) {
        assertEquals(null, actual);
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }

    private static Response json(Request request, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private static void testListsAllPagesAndOrdersFoldersAndNaturalNumbers() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    Request request = chain.request();
                                    assertEquals("drive", request.url().queryParameter("spaces"));
                                    assertEquals(
                                            "'root' in parents and trashed = false",
                                            request.url().queryParameter("q"));
                                    if (requests.getAndIncrement() == 0) {
                                        assertNull(request.url().queryParameter("pageToken"));
                                        return json(
                                                request,
                                                "{\"nextPageToken\":\"cursor-2\",\"files\":[{\"id\":\"ten\",\"name\":\"Comic10.cbz\",\"mimeType\":\"application/zip\",\"size\":\"15\"}]}");
                                    }
                                    assertEquals(
                                            "cursor-2", request.url().queryParameter("pageToken"));
                                    return json(
                                            request,
                                            "{\"files\":[{\"id\":\"two\",\"name\":\"Comic2.cbz\",\"mimeType\":\"application/zip\",\"size\":\"12\"},{\"id\":\"folder\",\"name\":\"Library\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}");
                                })
                        .build();
        List<DriveClient.Entry> entries = new DriveClient("token", http).list("root");
        assertEquals(2, requests.get());
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).directory());
        assertEquals("Comic2.cbz", entries.get(1).name());
        assertEquals(12L, entries.get(1).size());
        assertEquals("Comic10.cbz", entries.get(2).name());
    }

    private static void testRepeatedCursorDoesNotLoopAndMalformedListingsFail() throws Exception {
        for (String fixture :
                new String[] {
                    "{}",
                    "{\"incompleteSearch\":true,\"files\":[]}",
                    "{\"files\":[{\"id\":\"../bad\",\"name\":\"x.cbz\",\"mimeType\":\"application/zip\"}]}",
                    "{\"files\":[],\"nextPageToken\":\"repeated\"}"
                }) {
            AtomicInteger requests = new AtomicInteger();
            OkHttpClient http =
                    new OkHttpClient.Builder()
                            .addInterceptor(
                                    chain -> {
                                        requests.incrementAndGet();
                                        return json(chain.request(), fixture);
                                    })
                            .build();
            try {
                new DriveClient("token", http).list("root");
                fail("Accepted invalid listing");
            } catch (IOException expected) {
                assertTrue(requests.get() <= 2);
            }
        }
    }

    private static void testDuplicateFileIdsAreNotSilentlyAccepted() throws Exception {
        String fixture =
                "{\"files\":[{\"id\":\"same\",\"name\":\"a.cbz\",\"mimeType\":\"application/zip\"},{\"id\":\"same\",\"name\":\"b.cbz\",\"mimeType\":\"application/zip\"}]}";
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(chain -> json(chain.request(), fixture))
                        .build();
        try {
            new DriveClient("token", http).list("root");
            fail("Accepted duplicate IDs");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("duplicate IDs"));
        }
    }
}
