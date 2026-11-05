package org.comicreader.app;

import static org.junit.Assert.*;

import android.content.*;

import okhttp3.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ReadingShelfTest {
    private Context context;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WidgetTest.reset(context);
    }

    @After
    public void cleanup() throws Exception {
        WidgetTest.reset(context);
    }

    private ReadingShelf.Location source(String owner, String folder, String issue) {
        return new ReadingShelf.Location(
                "drive", owner, folder, issue, "Nova #00" + issue + ".cbz", 10);
    }

    @Test
    public void respectsFolderAndAccountScopesAndRetainsOriginsAcrossRefresh() throws Exception {
        Library.Book one = WidgetTest.comic(context, "Nova #001.cbz", 1),
                two = WidgetTest.comic(context, "Nova #002.cbz", 2);
        ReadingShelf shelf = ReadingShelf.get(context);
        shelf.link(one, source("A", "Folder", "1"));
        shelf.link(two, source("A", "Other", "2"));
        context.getSharedPreferences("drive", 0).edit().putString("account", "A").commit();
        assertNull(ReadingShelf.next(shelf.current(one.id(), null), shelf.items()));
        shelf.link(two, source("A", "Folder", "2"));
        assertEquals(two, shelf.current(two.id(), null).book());
        assertEquals(two, ReadingShelf.next(shelf.current(one.id(), null), shelf.items()).book());
        shelf.replace("drive", "A", "Folder", List.of(source("A", "Folder", "3")));
        assertEquals(two, shelf.current(two.id(), null).book());
        assertTrue(
                shelf.items().stream()
                        .anyMatch(item -> item.book() == null && item.title().contains("003")));
        context.getSharedPreferences("drive", 0).edit().putString("account", "B").commit();
        assertFalse(shelf.items().stream().anyMatch(item -> item.book() == null));
        assertEquals(2, Library.get(context).books().size());
    }

    @Test
    public void badCatalogRefreshIsAtomicAndAmbiguousDuplicateNumbersDoNotAutoAdvance()
            throws Exception {
        ReadingShelf shelf = ReadingShelf.get(context);
        shelf.replace("drive", "A", "Folder", List.of(source("A", "Folder", "1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> shelf.replace("drive", "A", "Folder", List.of(source("B", "Folder", "2"))));
        context.getSharedPreferences("drive", 0).edit().putString("account", "A").commit();
        assertEquals("Nova #001.cbz", shelf.items().get(0).title());
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1);
        shelf.link(first, source("A", "Folder", "1"));
        ReadingShelf.Location a = source("A", "Folder", "2");
        ReadingShelf.Location b =
                new ReadingShelf.Location("drive", "A", "Folder", "copy", "Nova #02.cbr", 10);
        shelf.replace("drive", "A", "Folder", List.of(source("A", "Folder", "1"), a, b));
        assertNull(ReadingShelf.next(shelf.current(first.id(), null), shelf.items()));
    }

    @Test
    public void suggestionsChooseLatestProgressAndNeverRepeatCompletedComics() throws Exception {
        Library library = Library.get(context);
        Library.Book one = WidgetTest.comic(context, "Nova #001.cbz", 1),
                two = WidgetTest.comic(context, "Nova #002.cbz", 2),
                three = WidgetTest.comic(context, "Nova #003.cbz", 3);
        library.update(one.id(), 2, true, false, new int[0]);
        library.update(two.id(), 1, false, false, new int[0]);
        List<ReadingShelf.Item> items = ReadingShelf.get(context).items();
        assertEquals(two, ReadingShelf.select(items, "continue", two.id()).get(0).book());
        assertEquals(
                List.of(two, three),
                ReadingShelf.select(items, "next", two.id()).stream()
                        .map(ReadingShelf.Item::book)
                        .toList());
        assertEquals(2, ReadingShelf.select(items, "shelf", two.id()).size());
    }

    @Test
    public void driveAccountIdentityIsValidatedAndReused() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    calls.incrementAndGet();
                                    assertEquals(
                                            "user(permissionId)",
                                            chain.request().url().queryParameter("fields"));
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
                                })
                        .build();
        DriveClient client = new DriveClient("Test", http);
        assertEquals("Owner", client.account());
        assertEquals("Owner", client.account());
        assertEquals(1, calls.get());
    }

    @Test
    public void removingACopyKeepsCloudProgressAndCompletedIssuesAreNotSuggestedAgain()
            throws Exception {
        Library library = Library.get(context);
        Library.Book first = WidgetTest.comic(context, "Nova #001.cbz", 1),
                second = WidgetTest.comic(context, "Nova #002.cbz", 2);
        ReadingShelf shelf = ReadingShelf.get(context);
        shelf.link(first, source("A", "Folder", "1"));
        shelf.link(second, source("A", "Folder", "2"));
        shelf.replace(
                "drive",
                "A",
                "Folder",
                List.of(
                        source("A", "Folder", "1"),
                        source("A", "Folder", "2"),
                        source("A", "Folder", "3")));
        context.getSharedPreferences("drive", 0).edit().putString("account", "A").commit();
        library.update(first.id(), 2, true, false, new int[0]);
        library.update(second.id(), 1, false, false, new int[0]);
        CoverStore.get(context).load(second.id(), second, null, null);
        library.remove(second);
        ReadingShelf.Item continuing =
                ReadingShelf.select(shelf.items(), "continue", second.id()).get(0);
        assertNull(continuing.book());
        assertEquals(second.id(), continuing.progress().id);
        assertEquals(1, continuing.progress().page);
        Map<String, android.graphics.Bitmap> images = new HashMap<>();
        ComicWidget.render(context, "continue", List.of(continuing), 160, 160, images);
        assertNotNull(images.get(second.id()));
        library.updateReading(second.id(), null, true, new int[0], new int[0]);
        assertTrue(ReadingShelf.select(shelf.items(), "continue", second.id()).isEmpty());
        assertEquals(
                "Nova #003.cbz",
                ReadingShelf.next(shelf.current(first.id(), null), shelf.items()).title());
    }

    @Test
    public void replacedProviderContentDoesNotHideTheNewCopyOrReuseItsPredecessorsProgress()
            throws Exception {
        Library library = Library.get(context);
        Library.Book old = WidgetTest.comic(context, "Nova #001.cbz", 1),
                updated = WidgetTest.comic(context, "Nova #001.cbz", 2);
        library.update(old.id(), 1, false, false, new int[0]);
        ReadingShelf shelf = ReadingShelf.get(context);
        ReadingShelf.Location location = source("A", "Folder", "1");
        shelf.link(old, location);
        shelf.link(updated, location);
        assertEquals(updated, shelf.current(updated.id(), location.key()).book());
        assertEquals(old, shelf.current(old.id(), null).book());
        assertEquals(0, library.state(updated.id()).page);
        assertEquals(1, library.state(old.id()).page);
        assertEquals(2, library.books().size());
    }
}
