package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.AlertDialog;
import android.os.*;
import android.view.*;
import android.widget.*;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import okhttp3.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-mdpi", shadows = PullRefreshTest.DavShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PullRefreshTest {
    private ActivityController<MainActivity> main;

    @Implements(value = WebDav.class, isInAndroidSdk = false)
    public static class DavShadow {
        static int calls;
        static String name = "Old.cbz";

        @Implementation
        protected List<WebDav.Entry> list(URI folder) {
            calls++;
            return name == null
                    ? List.of()
                    : List.of(new WebDav.Entry(folder.resolve("comic.cbz"), name, false, 7));
        }
    }

    @Before
    public void setup() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("covers", "off").commit();
        main = Robolectric.buildActivity(MainActivity.class).setup().visible();
        drain();
        layout(main.get().getWindow().getDecorView());
    }

    static void layout(View root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 411, 891);
    }

    @After
    public void close() {
        main.close();
    }

    private void drain() throws Exception {
        drain(main.get());
    }

    static void drain(MainActivity main) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(main, "worker");
        for (int i = 0; i < 3; i++) {
            worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        }
    }

    static void swipe(View target, float dx, float dy) {
        if (target.getParent() instanceof SwipeRefreshLayout parent) target = parent;
        assertTrue(
                "Gesture target must be laid out", target.getWidth() > 0 && target.getHeight() > 0);
        long time = SystemClock.uptimeMillis();
        float x = target.getWidth() / 2f, y = 24;
        for (int step = 0; step <= 5; step++) {
            int action =
                    step == 0
                            ? MotionEvent.ACTION_DOWN
                            : step == 5 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event =
                    MotionEvent.obtain(
                            time,
                            time + step * 30,
                            action,
                            x + dx * step / 5,
                            y + dy * step / 5,
                            0);
            target.dispatchTouchEvent(event);
            event.recycle();
        }
        if (target instanceof SwipeRefreshLayout pull && pull.isRefreshing()) {
            // Robolectric does not deliver the circle View animation's completion callback.
            android.view.animation.Animation.AnimationListener end =
                    ReflectionHelpers.getField(pull, "mRefreshListener");
            end.onAnimationEnd(null);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    static SwipeRefreshLayout pull(View root) {
        if (root instanceof SwipeRefreshLayout found) return found;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                SwipeRefreshLayout found = pull(group.getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private AlertDialog folder() {
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        layout(dialog.getWindow().getDecorView());
        assertNotNull(pull(dialog.getWindow().getDecorView()));
        return dialog;
    }

    @Test
    public void pullingAnEmptyLibraryReloadsIt() throws Exception {
        int before = ReflectionHelpers.getField(main.get(), "refreshGeneration");
        GridView shelf = ReflectionHelpers.getField(main.get(), "shelf");
        swipe((View) shelf.getParent(), 0, 240);
        drain();
        assertTrue(
                "Pull down must reload even an empty library; refreshing="
                        + ReflectionHelpers.<SwipeRefreshLayout>getField(main.get(), "shelfRefresh")
                                .isRefreshing(),
                ReflectionHelpers.<Integer>getField(main.get(), "refreshGeneration") > before);
    }

    @Test
    public void horizontalMovementDoesNotRefresh() throws Exception {
        int before = ReflectionHelpers.getField(main.get(), "refreshGeneration");
        GridView shelf = ReflectionHelpers.getField(main.get(), "shelf");
        swipe((View) shelf.getParent(), 160, 4);
        drain();
        assertEquals(
                before,
                ReflectionHelpers.<Integer>getField(main.get(), "refreshGeneration").intValue());
    }

    @Test
    public void pullFindsNewLocalEntriesAndStopsItsIndicator() throws Exception {
        UiScreensTest.fixture(main.get(), 3);
        GridView shelf = ReflectionHelpers.getField(main.get(), "shelf");
        assertEquals(0, shelf.getAdapter().getCount());
        swipe((View) shelf.getParent(), 0, 240);
        drain();
        assertEquals(1, shelf.getAdapter().getCount());
        assertFalse(
                ReflectionHelpers.<SwipeRefreshLayout>getField(main.get(), "shelfRefresh")
                        .isRefreshing());
    }

    @Test
    public void aScrolledListMustReachItsTopBeforeRefreshing() {
        ListView list = new ListView(main.get());
        list.setAdapter(
                new ArrayAdapter<>(
                        main.get(),
                        android.R.layout.simple_list_item_1,
                        java.util.stream.IntStream.range(0, 100)
                                .mapToObj(i -> "Comic " + i)
                                .toList()));
        SwipeRefreshLayout pull = Ui.refreshable(main.get(), list, "Empty");
        AtomicInteger calls = new AtomicInteger();
        Ui.onRefresh(pull, calls::incrementAndGet);
        main.get().setContentView(pull);
        layout(main.get().getWindow().getDecorView());
        list.setSelectionFromTop(20, 0);
        shadowOf(Looper.getMainLooper()).idle();
        layout(main.get().getWindow().getDecorView());
        assertTrue(list.canScrollList(-1));
        swipe(pull, 0, 240);
        assertEquals(0, calls.get());
        list.setSelectionFromTop(0, 0);
        shadowOf(Looper.getMainLooper()).idle();
        layout(main.get().getWindow().getDecorView());
        swipe(pull, 0, 240);
        assertEquals(1, calls.get());
    }

    @Test
    public void pullingWhileBusyDoesNotQueueAnotherOperation() throws Exception {
        ReflectionHelpers.setField(main.get(), "busy", true);
        int before = ReflectionHelpers.getField(main.get(), "refreshGeneration");
        swipe(ReflectionHelpers.getField(main.get(), "shelfRefresh"), 0, 240);
        assertEquals(
                before,
                ReflectionHelpers.<Integer>getField(main.get(), "refreshGeneration").intValue());
        assertFalse(
                ReflectionHelpers.<SwipeRefreshLayout>getField(main.get(), "shelfRefresh")
                        .isRefreshing());
        ReflectionHelpers.setField(main.get(), "busy", false);
    }

    @Test
    public void driveRefreshKeepsOldEntriesOnFailureThenLoadsNewOnes() throws Exception {
        AtomicInteger calls = new AtomicInteger(), status = new AtomicInteger(200);
        AtomicReference<String> name = new AtomicReference<>("Old.cbz");
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    if (chain.request().url().encodedPath().endsWith("/about"))
                                        return new Response.Builder()
                                                .request(chain.request())
                                                .protocol(Protocol.HTTP_1_1)
                                                .code(200)
                                                .message("Test")
                                                .body(
                                                        ResponseBody.create(
                                                                "{\"user\":{\"permissionId\":\"Owner\"}}",
                                                                MediaType.get("application/json")))
                                                .build();
                                    calls.incrementAndGet();
                                    assertTrue(
                                            chain.request()
                                                    .url()
                                                    .queryParameter("q")
                                                    .contains("'saved' in parents"));
                                    String body =
                                            "{\"files\":[{\"id\":\"comic\",\"name\":\""
                                                    + name.get()
                                                    + "\",\"mimeType\":\"application/zip\",\"size\":\"7\"}]}";
                                    return new Response.Builder()
                                            .request(chain.request())
                                            .protocol(Protocol.HTTP_1_1)
                                            .code(status.get())
                                            .message("Test")
                                            .body(
                                                    ResponseBody.create(
                                                            body,
                                                            MediaType.get("application/json")))
                                            .build();
                                })
                        .build();
        DriveClient drive = new DriveClient("Test", http);
        ReflectionHelpers.callInstanceMethod(
                main.get(),
                "browseDrive",
                from(DriveClient.class, drive),
                from(String.class, "saved"),
                from(Deque.class, new ArrayDeque<String>()));
        drain();
        AlertDialog original = folder();
        UiScreensTest.screenshot(original.getWindow().getDecorView(), 411, 891, "folder-items");
        SwipeRefreshLayout pull = pull(original.getWindow().getDecorView());
        status.set(503);
        swipe(pull, 0, 240);
        drain();
        assertEquals(2, calls.get());
        assertTrue(original.isShowing());
        assertEquals("Old.cbz", original.getListView().getAdapter().getItem(0));
        assertFalse(pull.isRefreshing());
        ShadowDialog.getLatestDialog().dismiss();
        status.set(200);
        name.set("New.cbz");
        swipe(pull, 0, 240);
        drain();
        assertEquals(3, calls.get());
        AlertDialog updated = folder();
        assertFalse(original.isShowing());
        assertEquals("New.cbz", updated.getListView().getAdapter().getItem(0));
        updated.getListView().performItemClick(null, 0, 0);
        assertEquals(
                main.get().getString(R.string.ui_download),
                ((AlertDialog) ShadowDialog.getLatestDialog())
                        .getButton(AlertDialog.BUTTON_POSITIVE)
                        .getText());
    }

    @Test
    public void webDavCanRefreshAnEmptyFolder() throws Exception {
        DavShadow.calls = 0;
        DavShadow.name = null;
        WebDav dav = new WebDav("https://example.test/comics/", "");
        ReflectionHelpers.callInstanceMethod(
                main.get(),
                "browse",
                from(WebDav.class, dav),
                from(URI.class, dav.root()),
                from(Deque.class, new ArrayDeque<URI>()));
        drain();
        AlertDialog empty = folder();
        UiScreensTest.screenshot(empty.getWindow().getDecorView(), 411, 891, "folder-empty");
        assertEquals(0, empty.getListView().getAdapter().getCount());
        DavShadow.name = "New.cbz";
        swipe(pull(empty.getWindow().getDecorView()), 0, 240);
        drain();
        assertEquals(2, DavShadow.calls);
        assertEquals("New.cbz", folder().getListView().getAdapter().getItem(0));
    }
}
