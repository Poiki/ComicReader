package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.widget.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ReaderSeriesTest {
    private Context context;
    private ActivityController<ReaderActivity> reader;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WidgetTest.reset(context);
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("covers", "off").commit();
    }

    @After
    public void close() throws Exception {
        if (reader != null) reader.close();
        WidgetTest.reset(context);
    }

    private ReaderActivity open(Library.Book book) throws Exception {
        reader =
                Robolectric.buildActivity(
                                ReaderActivity.class,
                                new Intent(context, ReaderActivity.class).putExtra("id", book.id()))
                        .setup()
                        .visible();
        PullRefreshTest.layout(reader.get().getWindow().getDecorView());
        WidgetTest.waitFor(
                () -> ReflectionHelpers.<Integer>getField(reader.get(), "displayedPage") >= 0);
        return reader.get();
    }

    @Test
    public void forwardFromTheRenderedFinalPageCommitsCompletionBeforeOpeningTheNextIssue()
            throws Exception {
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1),
                next = WidgetTest.comic(context, "Nova #002.cbz", 2);
        ReaderActivity activity = open(first);
        activity.advance(1);
        WidgetTest.waitFor(
                () -> ReflectionHelpers.<Integer>getField(activity, "displayedPage") == 1);
        activity.advance(1);
        WidgetTest.waitFor(
                () -> ReflectionHelpers.<Integer>getField(activity, "displayedPage") == 2);
        assertFalse(Library.get(context).state(first.id()).completed);
        assertNull(shadowOf(activity).peekNextStartedActivity());
        activity.advance(1);
        WidgetTest.waitFor(() -> shadowOf(activity).peekNextStartedActivity() != null);
        assertTrue(Library.get(context).state(first.id()).completed);
        assertEquals(2, Library.get(context).state(first.id()).page);
        assertEquals(next.id(), shadowOf(activity).getNextStartedActivity().getStringExtra("id"));
        assertFalse(
                context.getSharedPreferences("downloads", 0).getBoolean("delete_finished", false));
        java.util.concurrent.ThreadPoolExecutor worker =
                ReflectionHelpers.getField(activity, "worker");
        reader.close();
        reader = null;
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(Library.get(context).file(first).isFile());
    }

    @Test
    public void cleanupSettingStartsOffAndRetainsTheExplicitChoice() throws Exception {
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            ReflectionHelpers.callInstanceMethod(main.get(), "more");
            android.app.Dialog dialog = ShadowDialog.getLatestDialog();
            LinearLayout row =
                    dialog.getWindow()
                            .getDecorView()
                            .findViewWithTag(main.get().getString(R.string.delete_finished));
            Switch toggle = (Switch) row.getChildAt(0);
            assertFalse(toggle.isChecked());
            toggle.setChecked(true);
            assertTrue(
                    context.getSharedPreferences("downloads", 0)
                            .getBoolean("delete_finished", false));
            dialog.dismiss();
            ReflectionHelpers.callInstanceMethod(main.get(), "more");
            dialog = ShadowDialog.getLatestDialog();
            row =
                    dialog.getWindow()
                            .getDecorView()
                            .findViewWithTag(main.get().getString(R.string.delete_finished));
            assertTrue(((Switch) row.getChildAt(0)).isChecked());
            dialog.dismiss();
        }
    }

    @Test
    public void enabledCleanupRemovesTheClosedPrivateCopyAndKeepsProgressAndOriginal()
            throws Exception {
        Library library = Library.get(context);
        Library.Book book = WidgetTest.comic(context, "Standalone.cbz", 1);
        java.io.File original = new java.io.File(context.getCacheDir(), "original.cbz");
        java.nio.file.Files.copy(
                library.file(book).toPath(),
                original.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] originalBytes = java.nio.file.Files.readAllBytes(original.toPath());
        context.getSharedPreferences("downloads", 0)
                .edit()
                .putBoolean("delete_finished", true)
                .commit();
        library.update(book.id(), 2, false, true, new int[] {1});
        ReaderActivity activity = open(book);
        activity.advance(1);
        WidgetTest.waitFor(activity::isFinishing);
        java.util.concurrent.ThreadPoolExecutor worker =
                ReflectionHelpers.getField(activity, "worker");
        reader.close();
        reader = null;
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertNull(library.book(book.id()));
        assertFalse(library.file(book).exists());
        assertTrue(library.state(book.id()).completed);
        assertTrue(library.state(book.id()).favorite);
        assertArrayEquals(new int[] {1}, library.state(book.id()).bookmarks());
        assertArrayEquals(originalBytes, java.nio.file.Files.readAllBytes(original.toPath()));
    }

    @Test
    public void cleanupKeepsTheNextComicAndDoesNotRemoveAnUnfinishedCopy() throws Exception {
        Library library = Library.get(context);
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1),
                next = WidgetTest.comic(context, "Nova #002.cbz", 2);
        context.getSharedPreferences("downloads", 0)
                .edit()
                .putBoolean("delete_finished", true)
                .commit();
        library.update(first.id(), 2, false, false, new int[0]);
        ReaderActivity activity = open(first);
        assertFalse(library.state(first.id()).completed);
        assertTrue(library.file(first).isFile());
        activity.advance(1);
        WidgetTest.waitFor(() -> shadowOf(activity).peekNextStartedActivity() != null);
        assertEquals(next.id(), shadowOf(activity).getNextStartedActivity().getStringExtra("id"));
        java.util.concurrent.ThreadPoolExecutor worker =
                ReflectionHelpers.getField(activity, "worker");
        reader.close();
        reader = null;
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertFalse(library.file(first).exists());
        assertTrue(library.file(next).isFile());
        ReaderActivity unfinished = open(next);
        worker = ReflectionHelpers.getField(unfinished, "worker");
        reader.close();
        reader = null;
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(library.file(next).isFile());
        assertFalse(library.state(next.id()).completed);
    }

    @Test
    public void itNeverJumpsToAnotherFolderOrFinishesAnUnrenderedPage() throws Exception {
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1),
                other = WidgetTest.comic(context, "Nova #002.cbz", 2);
        ReadingShelf shelf = ReadingShelf.get(context);
        shelf.link(
                first,
                new ReadingShelf.Location(
                        "local", "", "A", first.id(), first.title(), first.size()));
        shelf.link(
                other,
                new ReadingShelf.Location(
                        "local", "", "B", other.id(), other.title(), other.size()));
        Library.get(context).update(first.id(), 2, false, false, new int[0]);
        ReaderActivity activity = open(first);
        ReflectionHelpers.setField(activity, "displayedPage", -1);
        activity.advance(1);
        WidgetTest.drain();
        assertFalse(Library.get(context).state(first.id()).completed);
        assertNull(shadowOf(activity).peekNextStartedActivity());
        ReflectionHelpers.setField(activity, "displayedPage", 2);
        activity.advance(1);
        WidgetTest.drain();
        WidgetTest.waitFor(() -> !ReflectionHelpers.<Boolean>getField(activity, "advancingComic"));
        assertTrue(Library.get(context).state(first.id()).completed);
        assertNull(shadowOf(activity).peekNextStartedActivity());
    }

    @Test
    public void pausingDuringTheCommitDoesNotLaunchAnotherComicInTheBackground() throws Exception {
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1);
        WidgetTest.comic(context, "Nova #002.cbz", 2);
        Library.get(context).update(first.id(), 2, false, false, new int[0]);
        ReaderActivity activity = open(first);
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1);
        Library.IO.execute(
                () -> {
                    started.countDown();
                    try {
                        gate.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            activity.advance(1);
            reader.pause();
        } finally {
            gate.countDown();
        }
        WidgetTest.drain();
        assertTrue(Library.get(context).state(first.id()).completed);
        assertNull(shadowOf(activity).peekNextStartedActivity());
    }

    @Test
    public void aFailedProgressCommitKeepsTheCurrentReaderAndReportsTheError() throws Exception {
        context.getSharedPreferences("downloads", 0)
                .edit()
                .putBoolean("delete_finished", true)
                .commit();
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1);
        WidgetTest.comic(context, "Nova #002.cbz", 2);
        Library library = Library.get(context);
        library.update(first.id(), 2, false, false, new int[0]);
        ReaderActivity activity = open(first);
        library.getWritableDatabase().delete("progress", "id=?", new String[] {first.id()});
        activity.advance(1);
        WidgetTest.drain();
        assertNull(shadowOf(activity).peekNextStartedActivity());
        assertNotNull(ShadowDialog.getLatestDialog());
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        assertTrue(ReflectionHelpers.getField(activity, "dirty"));
        assertTrue(library.file(first).isFile());
    }

    @Test
    public void aRemoteWidgetCannotBypassTheMeteredDownloadPolicy() throws Exception {
        ReadingShelf.Location source =
                new ReadingShelf.Location("drive", "Owner", "folder", "file", "Nova #002.cbz", 10);
        context.getSharedPreferences("drive", 0).edit().putString("account", "Owner").commit();
        ReadingShelf.get(context).replace("drive", "Owner", "folder", List.of(source));
        try (ActivityController<ReadActivity> main =
                Robolectric.buildActivity(
                                ReadActivity.class,
                                new Intent(context, ReadActivity.class)
                                        .setAction("org.comicreader.app.READ")
                                        .setData(android.net.Uri.parse(source.key())))
                        .setup()
                        .visible()) {
            ReflectionHelpers.<java.util.concurrent.Future<?>>getField(main.get(), "task")
                    .get(3, TimeUnit.SECONDS);
            WidgetTest.waitFor(() -> ReflectionHelpers.<Boolean>getField(main.get(), "failed"));
            TextView text = ReflectionHelpers.getField(main.get(), "status");
            assertEquals(context.getString(R.string.download_network_required), text.getText());
            assertTrue(Library.get(context).books().isEmpty());
        }
    }

    @Test
    public void aPreparedWidgetOpenWaitsUntilItsPrivateActivityResumes() throws Exception {
        Library.Book book = WidgetTest.comic(context, "Nova #001.cbz", 1);
        ReadingShelf.Location source =
                new ReadingShelf.Location(
                        "local", "", "folder", book.id(), book.title(), book.size());
        ReadingShelf.get(context).link(book, source);
        try (ActivityController<ReadActivity> controller =
                Robolectric.buildActivity(
                                ReadActivity.class,
                                new Intent(context, ReadActivity.class)
                                        .setAction("org.comicreader.app.READ")
                                        .setData(android.net.Uri.parse(source.key())))
                        .create()
                        .start()) {
            ReflectionHelpers.<java.util.concurrent.Future<?>>getField(controller.get(), "task")
                    .get(3, TimeUnit.SECONDS);
            WidgetTest.waitFor(() -> ReflectionHelpers.<Boolean>getField(controller.get(), "done"));
            assertNull(shadowOf(controller.get()).peekNextStartedActivity());
            controller.resume();
            assertEquals(
                    book.id(),
                    shadowOf(controller.get()).getNextStartedActivity().getStringExtra("id"));
        }
    }
}
