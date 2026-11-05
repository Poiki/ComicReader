package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class WidgetTest {
    private Context context;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        reset(context);
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("appearance", 0)
                .edit()
                .putString("covers", "off")
                .putString("theme", "oled")
                .commit();
    }

    @After
    public void cleanup() throws Exception {
        reset(context);
    }

    static void reset(Context context) throws Exception {
        drain();
        for (Class<?> type : List.of(Library.class, ReadingShelf.class, MegaLibrary.class)) {
            android.database.sqlite.SQLiteOpenHelper helper =
                    ReflectionHelpers.getStaticField(type, "instance");
            if (helper != null) helper.close();
            ReflectionHelpers.setStaticField(type, "instance", null);
        }
        ReflectionHelpers.setStaticField(MegaClient.class, "instance", null);
        ReflectionHelpers.setStaticField(CoverStore.class, "instance", null);
        ReflectionHelpers.setStaticField(ComicWidget.class, "installed", null);
        ReflectionHelpers.setStaticField(ComicWidget.class, "foreground", 0);
        for (String database : List.of("library.db", "reading-shelf.db", "mega-library.db"))
            context.deleteDatabase(database);
        for (String pref :
                List.of(
                        "library",
                        "reader",
                        "mega",
                        "drive",
                        "cloud",
                        "appearance",
                        "updates",
                        "downloads")) context.getSharedPreferences(pref, 0).edit().clear().commit();
    }

    static Library.Book comic(Context context, String title, int number) throws Exception {
        Bitmap cover = Bitmap.createBitmap(192, 288, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        int[] palette = {0xff345263, 0xff91603e, 0xff566949, 0xff66546f, 0xff874c50};
        canvas.drawColor(palette[Math.floorMod(number, palette.length)]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x33ffffff);
        canvas.drawCircle(172, 95, 116, paint);
        paint.setColor(0x33000000);
        canvas.drawRect(0, 175, 192, 288, paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(27);
        canvas.drawText(title.split(" #")[0].toUpperCase(Locale.ROOT), 12, 42, paint);
        paint.setTextSize(68);
        Series.Issue issue = Series.parse(title);
        canvas.drawText(
                String.format(
                        Locale.ROOT, "%02d", issue == null ? number : issue.number().intValue()),
                12,
                251,
                paint);
        paint.setTextSize(12);
        canvas.drawText("COMICREADER", 12, 274, paint);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        cover.compress(Bitmap.CompressFormat.PNG, 100, png);
        cover.recycle();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.setComment(title);
            for (int page = 1; page <= 3; page++) {
                zip.putNextEntry(new ZipEntry(page + ".png"));
                zip.write(png.toByteArray());
                zip.closeEntry();
            }
        }
        byte[] bytes = archive.toByteArray();
        Library.Book book =
                new Library.Book(
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        title,
                        "cbz",
                        bytes.length);
        Library library = Library.get(context);
        File staged = library.staging();
        Files.write(staged.toPath(), bytes);
        library.add(book, staged, 3);
        return book;
    }

    static void waitFor(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Expected asynchronous state was not reached");
    }

    static void drain() throws Exception {
        for (int i = 0; i < 3; i++) {
            Library.IO.submit(() -> {}).get(3, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static List<View> descendants(View root, int id) {
        List<View> views = new ArrayList<>();
        if (root.getId() == id) views.add(root);
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++)
                views.addAll(descendants(group.getChildAt(i), id));
        return views;
    }

    private static void bounds(View root) {
        if (root.getVisibility() != View.VISIBLE) return;
        if (root.getParent() instanceof ViewGroup parent) {
            assertTrue(
                    "Child fits vertical widget bounds: " + root.getId(),
                    root.getTop() >= 0 && root.getBottom() <= parent.getHeight());
            assertTrue(
                    "Child fits horizontal widget bounds: " + root.getId(),
                    root.getLeft() >= 0 && root.getRight() <= parent.getWidth());
        }
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) bounds(group.getChildAt(i));
    }

    private View apply(RemoteViews views, int width, int height) {
        FrameLayout host = new FrameLayout(context);
        View root = views.apply(context, host);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        host.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);
        bounds(root);
        return root;
    }

    @Test
    public void rendersResponsiveBilingualWidgetsAndOpensTheSelectedComic() throws Exception {
        Library library = Library.get(context);
        List<Library.Book> books = new ArrayList<>();
        for (int series = 0; series < 4; series++)
            for (int issue = 1; issue <= 3; issue++) {
                String name =
                        List.of("Nova", "Orbit", "Atlas", "Echo").get(series)
                                + " #00"
                                + issue
                                + " (2026).cbz";
                Library.Book book = comic(context, name, series * 3 + issue);
                books.add(book);
                if (issue == 1) library.update(book.id(), 2, true, false, new int[0]);
                else if (issue == 2) library.update(book.id(), 1, false, false, new int[0]);
                CoverStore.get(context).load(book.id(), book, null, null);
            }
        String last = books.get(1).id();
        context.getSharedPreferences("reader", 0).edit().putString("last_book", last).commit();
        List<ReadingShelf.Item> items = ReadingShelf.get(context).items();
        File directory = new File("build/reports/widgets");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        for (String mode : List.of("continue", "next", "shelf"))
            for (String theme : List.of("oled", "dark", "light")) {
                context.getSharedPreferences("appearance", 0)
                        .edit()
                        .putString("theme", theme)
                        .commit();
                List<ReadingShelf.Item> selected = ReadingShelf.select(items, mode, last);
                int previous = 0;
                for (int[] size :
                        new int[][] {{160, 160}, {320, 180}, {320, 340}, {480, 340}, {320, 500}}) {
                    View root =
                            apply(
                                    ComicWidget.render(
                                            context,
                                            mode,
                                            selected,
                                            size[0],
                                            size[1],
                                            new HashMap<>()),
                                    size[0],
                                    size[1]);
                    int count =
                            (int)
                                    descendants(root, R.id.widget_item).stream()
                                            .filter(view -> view.getVisibility() == View.VISIBLE)
                                            .count();
                    assertTrue(count >= previous);
                    previous = count;
                    Bitmap image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (OutputStream out =
                            new FileOutputStream(
                                    new File(
                                            directory,
                                            mode + "-" + theme + "-" + size[0] + "x" + size[1]
                                                    + ".png"))) {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
                    }
                    image.recycle();
                    if (mode.equals("continue") && theme.equals("oled") && size[0] == 160) {
                        descendants(root, R.id.widget_item).get(0).performClick();
                        Intent started =
                                shadowOf((Application) RuntimeEnvironment.getApplication())
                                        .getNextStartedActivity();
                        assertEquals(
                                ReaderActivity.class.getName(),
                                started.getComponent().getClassName());
                        assertEquals(last, started.getStringExtra("id"));
                    }
                }
            }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.fontScale = 1.7f;
        config.setLocale(Locale.forLanguageTag("es"));
        Context spanish = context.createConfigurationContext(config);
        for (int[] size : new int[][] {{140, 140}, {280, 160}, {320, 320}, {320, 480}}) {
            RemoteViews views =
                    ComicWidget.render(
                            spanish,
                            "shelf",
                            ReadingShelf.select(items, "shelf", last),
                            size[0],
                            size[1],
                            new HashMap<>());
            FrameLayout host = new FrameLayout(spanish);
            View root = views.apply(spanish, host);
            host.addView(root, new FrameLayout.LayoutParams(-1, -1));
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
            host.layout(0, 0, size[0], size[1]);
            bounds(root);
            assertEquals(
                    "Estantería de lectura",
                    ((TextView) root.findViewById(R.id.widget_title)).getText());
            Bitmap image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (OutputStream out =
                    new FileOutputStream(
                            new File(
                                    directory,
                                    "shelf-es-large-" + size[0] + "x" + size[1] + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            image.recycle();
        }
    }

    @Test
    @Config(sdk = {28, 34})
    public void smallestWidgetShowsBothUnfinishedComicsAndOpensEachOne() throws Exception {
        Library library = Library.get(context);
        Library.Book first = comic(context, "Nova #001.cbz", 1),
                last = comic(context, "Orbit #001.cbz", 2),
                finished = comic(context, "Atlas #001.cbz", 3);
        library.update(first.id(), 1, false, false, new int[0]);
        library.update(last.id(), 1, false, false, new int[0]);
        library.update(finished.id(), 2, true, false, new int[0]);
        CoverStore.get(context).load(first.id(), first, null, null);
        CoverStore.get(context).load(last.id(), last, null, null);
        context.getSharedPreferences("reader", 0).edit().putString("last_book", last.id()).commit();
        List<ReadingShelf.Item> items =
                ReadingShelf.select(ReadingShelf.get(context).items(), "continue", last.id());
        assertEquals(
                List.of(last.id(), first.id()),
                items.stream().map(ReadingShelf.Item::identity).toList());
        for (float font : new float[] {1, 1.7f}) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.fontScale = font;
            context = context.createConfigurationContext(config);
            View root =
                    apply(
                            ComicWidget.render(
                                    context, "continue", items, 140, 140, new HashMap<>()),
                            140,
                            140);
            List<View> cards = descendants(root, R.id.widget_item);
            assertEquals(2, cards.size());
            for (int index = 0; index < cards.size(); index++) {
                View card = cards.get(index);
                assertEquals(View.VISIBLE, card.getVisibility());
                card.performClick();
                Intent opened =
                        shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
                assertEquals(items.get(index).identity(), opened.getStringExtra("id"));
                assertEquals(
                        items.get(index).location().key(),
                        opened.getStringExtra("reading_location"));
            }
            File image =
                    new File(
                            "build/reports/widgets/continue-two-api"
                                    + Build.VERSION.SDK_INT
                                    + "-font"
                                    + font
                                    + ".png");
            assertTrue(image.getParentFile().isDirectory() || image.getParentFile().mkdirs());
            Bitmap bitmap = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            try (OutputStream out = new FileOutputStream(image)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            bitmap.recycle();
        }
    }

    @Test
    public void cachedRemoteTilesUseTheExistingDownloadRouteAndEmptyWidgetsOpenTheLibrary()
            throws Exception {
        ReadingShelf.Location location =
                new ReadingShelf.Location("drive", "Owner", "folder", "id", "Nova #002.cbz", 123);
        ReadingShelf.Item item = new ReadingShelf.Item(location, null, null, false);
        View root =
                apply(
                        ComicWidget.render(
                                context, "next", List.of(item), 160, 160, new HashMap<>()),
                        160,
                        160);
        descendants(root, R.id.widget_item).get(0).performClick();
        Intent started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals("org.comicreader.app.READ", started.getAction());
        assertEquals(ReadActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(location.key(), started.getDataString());
        root =
                apply(
                        ComicWidget.render(
                                context, "continue", List.of(), 160, 160, new HashMap<>()),
                        160,
                        160);
        root.findViewById(R.id.widget_empty).performClick();
        assertEquals(
                "org.comicreader.app.LIBRARY",
                shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity().getAction());
    }

    @Test
    public void providerPublishesResponsiveViewsAndNeverSchedulesPeriodicUpdates()
            throws Exception {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = shadowOf(manager).createWidget(ComicWidget.Shelf.class, R.layout.widget_shelf);
        drain();
        ComicWidget.updateAll(context);
        assertNotNull(shadowOf(manager).getViewFor(id));
        assertFalse(
                context.getPackageManager()
                        .getActivityInfo(new ComponentName(context, ReadActivity.class), 0)
                        .exported);
        for (Class<?> type :
                List.of(
                        ComicWidget.Continue.class,
                        ComicWidget.Next.class,
                        ComicWidget.Shelf.class)) {
            var info =
                    context.getPackageManager()
                            .getReceiverInfo(
                                    new ComponentName(context, type),
                                    android.content.pm.PackageManager.GET_META_DATA);
            assertFalse(info.exported);
            int xml = info.metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
            try (var parser = context.getResources().getXml(xml)) {
                while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {}
                assertEquals(
                        0,
                        parser.getAttributeIntValue(
                                "http://schemas.android.com/apk/res/android",
                                "updatePeriodMillis",
                                -1));
            }
        }
    }

    @Test
    public void responsiveBitmapBankStaysWithinTheSmallestWidgetBudget() throws Exception {
        for (int i = 1; i <= 9; i++) {
            Library.Book book = comic(context, "Nova #00" + i + ".cbz", i);
            CoverStore.get(context).load(book.id(), book, null, null);
        }
        List<ReadingShelf.Item> items =
                ReadingShelf.select(ReadingShelf.get(context).items(), "shelf", "");
        Map<String, Bitmap> images = new HashMap<>();
        Map<android.util.SizeF, RemoteViews> variants = new LinkedHashMap<>();
        for (int[] size : new int[][] {{140, 140}, {280, 160}, {280, 320}, {420, 320}, {280, 480}})
            variants.put(
                    new android.util.SizeF(size[0], size[1]),
                    ComicWidget.render(context, "shelf", items, size[0], size[1], images));
        long memory =
                images.values().stream()
                        .filter(Objects::nonNull)
                        .mapToLong(Bitmap::getAllocationByteCount)
                        .sum();
        assertTrue("Bound cover and progress allocations: " + memory, memory <= 6L * 140 * 140);
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            new RemoteViews(variants).writeToParcel(parcel, 0);
            assertTrue("Bound IPC payload", parcel.dataSize() < 512 * 1024);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void continuationKeepsTheLastFolderWhenTheSameComicHasMultipleOrigins()
            throws Exception {
        Library library = Library.get(context);
        Library.Book book = comic(context, "Nova #001.cbz", 1);
        library.update(book.id(), 1, false, false, new int[0]);
        context.getSharedPreferences("mega", 0).edit().putBoolean("connected", true).commit();
        context.getSharedPreferences("library", 0)
                .edit()
                .putString("mega_account", "Owner")
                .commit();
        MegaLibrary catalog = MegaLibrary.get(context);
        catalog.replace(
                "Owner",
                1,
                "First",
                List.of(new MegaClient.Node(11, book.title(), false, book.size())));
        catalog.replace(
                "Owner",
                2,
                "Second",
                List.of(new MegaClient.Node(21, book.title(), false, book.size())));
        catalog.link(context, "Owner", 11, book);
        catalog.link(context, "Owner", 21, book);
        ReadingShelf.Location first =
                new ReadingShelf.Location("mega", "Owner", "1", "11", book.title(), book.size());
        ReadingShelf.Location second =
                new ReadingShelf.Location("mega", "Owner", "2", "21", book.title(), book.size());
        ReadingShelf shelf = ReadingShelf.get(context);
        shelf.link(book, first);
        shelf.link(book, second);
        assertEquals(first.key(), shelf.current(book.id(), first.key()).location().key());
        assertEquals(second.key(), shelf.current(book.id(), null).location().key());
        context.getSharedPreferences("reader", 0)
                .edit()
                .putString("last_book", book.id())
                .putString("last_location", second.key())
                .commit();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = shadowOf(manager).createWidget(ComicWidget.Continue.class, R.layout.widget_shelf);
        drain();
        ComicWidget.updateAll(context);
        List<View> cards = descendants(shadowOf(manager).getViewFor(id), R.id.widget_item);
        assertEquals(1, cards.size());
        cards.get(0).performClick();
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(book.id(), intent.getStringExtra("id"));
        assertEquals(second.key(), intent.getStringExtra("reading_location"));
        MainActivity main = Robolectric.buildActivity(MainActivity.class).get();
        try {
            ReflectionHelpers.callInstanceMethod(main, "resumeLastBook");
            waitFor(() -> shadowOf(main).peekNextStartedActivity() != null);
            Intent resumed = shadowOf(main).getNextStartedActivity();
            assertEquals(book.id(), resumed.getStringExtra("id"));
            assertEquals(second.key(), resumed.getStringExtra("reading_location"));
        } finally {
            ReflectionHelpers.<java.util.concurrent.ExecutorService>getField(main, "worker")
                    .shutdownNow();
        }
    }
}
