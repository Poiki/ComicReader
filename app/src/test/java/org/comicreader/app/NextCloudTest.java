package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.net.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 34,
        qualifiers = "w411dp-h891dp-mdpi",
        shadows = {
            NextCloudTest.Auth.class,
            NextCloudTest.Drive.class,
            NextCloudTest.Credentials.class,
            NextCloudTest.Dav.class
        })
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NextCloudTest {
    @Implements(value = Secrets.class, isInAndroidSdk = false)
    public static class Credentials {
        static String header = "";

        @Implementation
        protected static String authorization(Context context) {
            return header;
        }
    }

    @Implements(value = WebDav.class, isInAndroidSdk = false)
    public static class Dav {
        @Implementation
        protected InputStream download(java.net.URI uri) {
            throw new AssertionError("Mismatched accounts must not transfer data");
        }
    }

    @Implements(value = DriveAuth.class, isInAndroidSdk = false)
    public static class Auth {
        @Implementation
        protected static String silent(Context context) {
            return "Test";
        }
    }

    @Implements(value = DriveClient.class, isInAndroidSdk = false)
    public static class Drive {
        static String owner = "Owner", folder, downloaded;
        static byte[] archive;

        @Implementation
        protected String account() {
            return owner;
        }

        @Implementation
        protected List<DriveClient.Entry> list(String id) {
            folder = id;
            return List.of(new DriveClient.Entry("two", "Nova #002.cbz", false, archive.length));
        }

        @Implementation
        protected InputStream download(String id) {
            downloaded = id;
            return new ByteArrayInputStream(archive);
        }
    }

    private Context context;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WidgetTest.reset(context);
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("covers", "off").commit();
        Drive.owner = "Owner";
        Drive.folder = Drive.downloaded = null;
        Credentials.header = "";
    }

    @After
    public void close() throws Exception {
        WidgetTest.reset(context);
    }

    @Test
    public void nextIssueRefreshesTheOriginalFolderAndImportsBeforeOpeningTheReader()
            throws Exception {
        Library library = Library.get(context);
        Library.Book current = WidgetTest.comic(context, "Nova #001.cbz", 1),
                next = WidgetTest.comic(context, "Nova #002.cbz", 2);
        Drive.archive = Files.readAllBytes(library.file(next).toPath());
        library.remove(next);
        library.update(current.id(), 2, true, false, new int[0]);
        ReadingShelf.Location location =
                new ReadingShelf.Location(
                        "drive",
                        "Owner",
                        "original-folder",
                        "one",
                        current.title(),
                        current.size());
        ReadingShelf.get(context).link(current, location);
        context.getSharedPreferences("drive", 0).edit().putString("account", "Owner").commit();
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        shadowOf(network)
                .setActiveNetworkInfo(
                        ShadowNetworkInfo.newInstance(
                                NetworkInfo.DetailedState.CONNECTED,
                                ConnectivityManager.TYPE_WIFI,
                                0,
                                true,
                                true));
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        shadowOf(network).setNetworkCapabilities(network.getActiveNetwork(), capabilities);
        assertTrue(Cloud.downloadAllowed(context));
        Intent intent =
                new Intent(context, ReadActivity.class)
                        .setAction("org.comicreader.app.NEXT")
                        .putExtra("finished_book", current.id())
                        .putExtra("reading_location", location.key());
        try (ActivityController<ReadActivity> main =
                Robolectric.buildActivity(ReadActivity.class, intent).setup().visible()) {
            WidgetTest.waitFor(() -> shadowOf(main.get()).peekNextStartedActivity() != null);
            Intent reading = shadowOf(main.get()).getNextStartedActivity();
            assertEquals("original-folder", Drive.folder);
            assertEquals("two", Drive.downloaded);
            assertEquals(next.id(), reading.getStringExtra("id"));
            assertTrue(library.file(library.book(next.id())).isFile());
            assertTrue(library.state(current.id()).completed);
            assertEquals(
                    "Owner",
                    ReadingShelf.get(context)
                            .current(next.id(), reading.getStringExtra("reading_location"))
                            .location()
                            .owner());
            org.robolectric.util.ReflectionHelpers.<java.util.concurrent.Future<?>>getField(
                            main.get(), "task")
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    public void aChangedAccountCannotDownloadThePreviousAccountsComic() throws Exception {
        Drive.owner = "Other";
        ReadingShelf.Location location =
                new ReadingShelf.Location("drive", "Owner", "original", "two", "Nova #002.cbz", 10);
        assertThrows(IOException.class, () -> ReadingShelf.get(context).download(location));
        assertNull(Drive.downloaded);
        assertTrue(Library.get(context).books().isEmpty());
    }

    @Test
    public void theSharedDownloadGuardStopsAMeteredHandoverAndReleasesItsCallback()
            throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        shadowOf(manager)
                .setActiveNetworkInfo(
                        ShadowNetworkInfo.newInstance(
                                NetworkInfo.DetailedState.CONNECTED,
                                ConnectivityManager.TYPE_WIFI,
                                0,
                                true,
                                true));
        NetworkCapabilities allowed = ShadowNetworkCapabilities.newInstance();
        shadowOf(allowed).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadowOf(allowed).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        shadowOf(manager).setNetworkCapabilities(manager.getActiveNetwork(), allowed);
        java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> download =
                    worker.submit(
                            () ->
                                    Cloud.download(
                                            context,
                                            () -> {
                                                NetworkCapabilities metered =
                                                        ShadowNetworkCapabilities.newInstance();
                                                shadowOf(metered)
                                                        .addCapability(
                                                                NetworkCapabilities
                                                                        .NET_CAPABILITY_VALIDATED);
                                                shadowOf(manager)
                                                        .setNetworkCapabilities(
                                                                manager.getActiveNetwork(),
                                                                metered);
                                                for (ConnectivityManager.NetworkCallback callback :
                                                        shadowOf(manager).getNetworkCallbacks())
                                                    callback.onCapabilitiesChanged(
                                                            manager.getActiveNetwork(), metered);
                                                assertTrue(Thread.currentThread().isInterrupted());
                                                throw new InterruptedException(
                                                        "Test network changed");
                                            }));
            try {
                download.get(3, java.util.concurrent.TimeUnit.SECONDS);
                fail("Metered handover must stop the transfer");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertEquals(
                        context.getString(R.string.download_network_changed),
                        expected.getCause().getMessage());
            }
            assertTrue(shadowOf(manager).getNetworkCallbacks().isEmpty());
            assertFalse(worker.submit(() -> Thread.currentThread().isInterrupted()).get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void webDavReconnectsKeepNamedIdentityWithoutAcceptingAnotherUser() throws Exception {
        context.getSharedPreferences("cloud", 0)
                .edit()
                .putString("endpoint", "https://example.test/comics")
                .commit();
        Credentials.header =
                "Basic "
                        + android.util.Base64.encodeToString(
                                "reader:old-password"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP);
        String original = Cloud.client(context).owner;
        Credentials.header =
                "Basic "
                        + android.util.Base64.encodeToString(
                                "reader:new-password"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP);
        assertEquals(original, Cloud.client(context).owner);
        assertEquals(Secrets.webDavIdentity("https://example.test/comics/", "reader"), original);
        Credentials.header =
                "Basic "
                        + android.util.Base64.encodeToString(
                                "other:new-password"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP);
        assertNotEquals(original, Cloud.client(context).owner);
        ReadingShelf.Location location =
                new ReadingShelf.Location(
                        "webdav",
                        original,
                        "https://example.test/comics/",
                        "https://example.test/comics/2.cbz",
                        "Nova #002.cbz",
                        10);
        IOException error =
                assertThrows(IOException.class, () -> ReadingShelf.get(context).download(location));
        assertEquals("Reconnect the original WebDAV account", error.getMessage());
    }
}
