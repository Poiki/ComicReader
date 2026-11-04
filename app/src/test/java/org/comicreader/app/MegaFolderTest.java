package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;

import nz.mega.sdk.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.*;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 34,
        qualifiers = "w411dp-h891dp-mdpi",
        shadows = MegaFolderTest.ApiShadow.class,
        instrumentedPackages = "nz.mega.sdk")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MegaFolderTest {
    @Implements(value = MegaApiJava.class, isInAndroidSdk = false)
    public static class ApiShadow {
        @Implementation
        protected void __constructor__(
                String key, String agent, String path, MegaGfxProcessor processor) {}
    }

    private static final class Reply extends MegaRequest {
        private final int type;

        Reply(int type) {
            super(0, false);
            this.type = type;
        }

        @Override
        public int getType() {
            return type;
        }

        @Override
        public long getNodeHandle() {
            return -1;
        }
    }

    private static final class Success extends MegaError {
        Success() {
            super(0, false);
        }

        @Override
        public int getErrorCode() {
            return API_OK;
        }
    }

    private static final class Folder extends MegaNode {
        private final String name;
        private final long handle;

        Folder() {
            this("Comics", 123);
        }

        Folder(String name, long handle) {
            super(0, false);
            this.name = name;
            this.handle = handle;
        }

        @Override
        public boolean isFolder() {
            return true;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getHandle() {
            return handle;
        }

        @Override
        public long getSize() {
            return 0;
        }
    }

    private static final class Api extends MegaApiJava {
        final AtomicInteger fetches = new AtomicInteger(), logouts = new AtomicInteger();
        volatile boolean available = true, restore = true;
        volatile int loggedIn = 1;
        volatile String name, nextName;

        Api() {
            super(null, "Test", null, null);
        }

        @Override
        public int isLoggedIn() {
            return loggedIn;
        }

        @Override
        public String getMyUserHandle() {
            return "Owner";
        }

        @Override
        public MegaNode getNodeByHandle(long handle) {
            return available ? new Folder() : null;
        }

        @Override
        public MegaNode getRootNode() {
            return available ? new Folder() : null;
        }

        @Override
        public ArrayList<MegaNode> getChildren(MegaNode parent) {
            return name == null
                    ? new ArrayList<>()
                    : new ArrayList<>(List.of(new Folder(name, 456)));
        }

        @Override
        public void fetchNodes(MegaRequestListenerInterface listener) {
            fetches.incrementAndGet();
            if (nextName != null) name = nextName;
            if (restore) available = true;
            listener.onRequestFinish(this, new Reply(MegaRequest.TYPE_FETCH_NODES), new Success());
        }

        @Override
        public void localLogout(MegaRequestListenerInterface listener) {
            logouts.incrementAndGet();
            loggedIn = 0;
            listener.onRequestFinish(this, new Reply(MegaRequest.TYPE_LOGOUT), new Success());
        }

        @Override
        public void logout(MegaRequestListenerInterface listener) {
            localLogout(listener);
        }
    }

    private Context context;
    private MegaClient client;
    private Api api;
    private ExecutorService worker;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("covers", "off").commit();
        context.getSharedPreferences("mega", 0).edit().putBoolean("connected", true).commit();
        ReflectionHelpers.setStaticField(MegaClient.class, "instance", null);
        client = MegaClient.instance(context);
        api = new Api();
        ReflectionHelpers.setField(client, "api", api);
        ReflectionHelpers.setField(client, "ready", true);
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void close() {
        worker.shutdownNow();
    }

    @Test
    public void missingSdkFolderReloadsTheTreeBeforeFailing() throws Exception {
        for (long handle : new long[] {123, MegaClient.ROOT}) {
            api.available = false;
            assertTrue(worker.submit(() -> client.list(handle)).get(2, TimeUnit.SECONDS).isEmpty());
        }
        assertEquals(2, api.fetches.get());
        assertEquals(0, api.logouts.get());
    }

    @Test
    public void validFolderUsesTheLoadedTree() throws Exception {
        assertTrue(worker.submit(() -> client.list(123)).get(2, TimeUnit.SECONDS).isEmpty());
        assertEquals(0, api.fetches.get());
    }

    @Test
    public void initialTreeLoadDoesNotRetryAgainForAMissingFolder() throws Exception {
        ReflectionHelpers.setField(client, "ready", false);
        genuinelyMissingFolderRemainsAnErrorAfterOneReload();
    }

    @Test
    public void explicitRefreshFetchesExactlyOneTree() throws Exception {
        worker.submit(() -> client.list(123, true)).get(2, TimeUnit.SECONDS);
        assertEquals(1, api.fetches.get());
        ReflectionHelpers.setField(client, "ready", false);
        worker.submit(() -> client.list(123, true)).get(2, TimeUnit.SECONDS);
        assertEquals(2, api.fetches.get());
    }

    @Test
    public void pullingTheLibraryGetsServerChangesAndKeepsTheCurrentFilter() throws Exception {
        api.name = "Old";
        context.getSharedPreferences("library", 0)
                .edit()
                .putString("source", "mega")
                .putString("mega_account", "Owner")
                .putLong("mega_root", 123)
                .putString("mega_name", "Comics")
                .commit();
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            PullRefreshTest.drain(main.get());
            assertEquals(
                    "Old",
                    MegaLibrary.get(context).snapshot("Owner", 123).entries().get(0).node().name());
            ReflectionHelpers.callInstanceMethod(
                    main.get(), "showLibrary", ReflectionHelpers.ClassParameter.from(int.class, 1));
            PullRefreshTest.layout(main.get().getWindow().getDecorView());
            api.nextName = "New";
            PullRefreshTest.swipe(
                    PullRefreshTest.pull(main.get().getWindow().getDecorView()), 0, 240);
            PullRefreshTest.drain(main.get());
            assertEquals(1, api.fetches.get());
            assertEquals(
                    "New",
                    MegaLibrary.get(context).snapshot("Owner", 123).entries().get(0).node().name());
            assertEquals(
                    1, ReflectionHelpers.<Integer>getField(main.get(), "selection").intValue());
            assertEquals(
                    123L, ReflectionHelpers.<Long>getField(main.get(), "megaFolder").longValue());
        }
    }

    @Test
    public void anEmptyPickerRefreshesAndSavesTheUpdatedListing() throws Exception {
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            PullRefreshTest.drain(main.get());
            main.get()
                    .showMegaFolderPicker(
                            client,
                            123,
                            "Comics",
                            new ArrayDeque<>(),
                            new MainActivity.FolderListing("Owner", List.of()));
            AlertDialog original = (AlertDialog) ShadowDialog.getLatestDialog();
            PullRefreshTest.layout(original.getWindow().getDecorView());
            assertEquals(0, original.getListView().getAdapter().getCount());
            api.nextName = "New";
            PullRefreshTest.swipe(
                    PullRefreshTest.pull(original.getWindow().getDecorView()), 0, 240);
            PullRefreshTest.drain(main.get());
            assertEquals(1, api.fetches.get());
            assertFalse(original.isShowing());
            AlertDialog updated = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("New", updated.getListView().getAdapter().getItem(0));
            updated.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            PullRefreshTest.drain(main.get());
            assertEquals(
                    "New",
                    MegaLibrary.get(context).snapshot("Owner", 123).entries().get(0).node().name());
        }
    }

    @Test
    public void genuinelyMissingFolderRemainsAnErrorAfterOneReload() throws Exception {
        api.available = api.restore = false;
        try {
            worker.submit(() -> client.list(123)).get(2, TimeUnit.SECONDS);
            fail("A missing folder must not become a successful empty listing");
        } catch (ExecutionException expected) {
            assertEquals(
                    "The MEGA folder is no longer available", expected.getCause().getMessage());
        }
        assertEquals(1, api.fetches.get());
    }

    @Test
    public void idleCleanupDoesNotWaitForAnUnfinishedRequest() throws Exception {
        Class<?> type = Class.forName(MegaClient.class.getName() + "$Request");
        Constructor<?> constructor = type.getDeclaredConstructor(MegaClient.class, File.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(client, null);
        CountDownLatch done = ReflectionHelpers.getField(pending, "done");
        ReflectionHelpers.setField(client, "pendingRequest", pending);
        try {
            assertEquals("Owner", worker.submit(client::account).get(1, TimeUnit.SECONDS));
            worker.submit(
                            () -> {
                                client.suspend();
                                return null;
                            })
                    .get(500, TimeUnit.MILLISECONDS);
            assertSame(pending, ReflectionHelpers.getField(client, "pendingRequest"));
            assertEquals(0, api.logouts.get());
        } finally {
            ReflectionHelpers.setField(client, "pendingRequest", null);
            done.countDown();
        }
    }

    @Test
    public void resumedLibraryKeepsItsSdkSession() throws Exception {
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            worker.submit(
                            () -> {
                                client.suspend();
                                return null;
                            })
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    "Background cleanup must not disconnect a visible library",
                    0,
                    api.logouts.get());
            assertEquals("Owner", worker.submit(client::account).get(1, TimeUnit.SECONDS));
            main.pause();
            waitFor(() -> api.logouts.get() == 1);
        }
    }

    @Test
    public void interruptedCleanupDoesNotStartLogout() throws Exception {
        worker.submit(
                        () -> {
                            Thread.currentThread().interrupt();
                            try {
                                client.suspend();
                                assertTrue(Thread.currentThread().isInterrupted());
                            } finally {
                                Thread.interrupted();
                            }
                            return null;
                        })
                .get(1, TimeUnit.SECONDS);
        assertEquals(0, api.logouts.get());
    }

    @Test
    public void explicitLogoutStillRevokesAVisibleSession() throws Exception {
        context.getSharedPreferences("secrets", 0)
                .edit()
                .putString("mega.session", "Test")
                .commit();
        client.browsing(true);
        try {
            worker.submit(
                            () -> {
                                client.logout();
                                return null;
                            })
                    .get(1, TimeUnit.SECONDS);
            assertEquals(1, api.logouts.get());
            assertFalse(client.loggedIn());
            assertNull(Secrets.get(context, "mega.session"));
        } finally {
            client.browsing(false);
        }
    }

    @Test
    public void automaticRefreshKeepsCachedFolderAndShowsInlineFailure() throws Exception {
        api.available = api.restore = false;
        context.getSharedPreferences("library", 0)
                .edit()
                .putString("source", "mega")
                .putString("mega_account", "Owner")
                .putLong("mega_root", 123)
                .putString("mega_name", "Comics")
                .commit();
        MegaLibrary.get(context)
                .replace(
                        "Owner",
                        123,
                        "Comics",
                        List.of(new MegaClient.Node(456, "Child", true, 0)));
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            waitFor(
                    () ->
                            !ReflectionHelpers.<Boolean>getField(main.get(), "busy")
                                    && !ReflectionHelpers.<String>getField(
                                                    main.get(), "catalogError")
                                            .isEmpty());
            Dialog modal = ShadowDialog.getLatestDialog();
            assertTrue(
                    "Automatic folder checks must not interrupt navigation with a dialog",
                    modal == null || !modal.isShowing());
            TextView status = ReflectionHelpers.getField(main.get(), "status");
            assertEquals(View.VISIBLE, status.getVisibility());
            assertFalse(status.getText().toString().isBlank());
            assertTrue(status.isClickable());
            assertTrue(
                    status.getText()
                            .toString()
                            .contains(context.getString(R.string.error_mega_folder)));
            assertEquals(1, MegaLibrary.get(context).snapshot("Owner", 123).entries().size());
            status.performClick();
            waitFor(
                    () ->
                            ShadowDialog.getLatestDialog() != null
                                    && ShadowDialog.getLatestDialog().isShowing());
        }
    }

    private static void waitFor(BooleanSupplier check) throws Exception {
        for (int i = 0; i < 150; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (check.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Expected UI/session state was not reached");
    }
}
