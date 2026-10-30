package org.comicreader.app;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.GridView;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** No test runtime in the shipped application. Runs on a disposable emulator. */
public final class SmokeInstrumentation extends Instrumentation {
    private int passed;
    private boolean megaOnly, megaNetwork, upgradeVerify, updateSeed;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        megaOnly = args != null && "true".equals(args.getString("mega"));
        megaNetwork = args != null && "true".equals(args.getString("network"));
        upgradeVerify = args != null && "true".equals(args.getString("upgradeVerify"));
        updateSeed = args != null && "true".equals(args.getString("updateSeed"));
        start();
    }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            Context context = getTargetContext();
            if (updateSeed) {
                context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
                testZipAndPdf(context); Library.get(context).update(imported.id(), 1, false, true, new int[]{0});
                results.putString("stream", "PASS seeded self-update fixture with comic, page2, favorite and bookmark\n"); finish(Activity.RESULT_OK, results); return;
            }
            if (upgradeVerify) {
                Library library = Library.get(context);
                    Library.Book book = library.books().stream().filter(item -> item.title().equals("Smoke test.cbz")).findFirst().orElseThrow();
                    Progress state = library.state(book.id());
                    check(state.page == 1 && state.favorite && state.bookmarked(0) && library.file(book).isFile(), "APK update must preserve comics and reading state");
                results.putString("stream", "PASS upgrade verification on new APK: comic, page, bookmark and favorite\n");
                finish(Activity.RESULT_OK, results); return;
            }
            if (megaOnly) {
                results.putString("stream", MegaNativeChecks.run(context, megaNetwork));
                finish(Activity.RESULT_OK, results); return;
            }
            testZipAndPdf(context); pass("CBZ/PDF decode, natural order, rejection, lifecycle");
            testStorage(context); pass("SQLite persistence, transactional conflict and overflow rejection");
            testSecrets(context); pass("Keystore roundtrip and encrypted session removal");
            testJobConstraints(context); pass("Opt-in one-shot sync, unmetered network and battery constraint");
            testDavParser(); pass("Android SAX WebDAV parser and URL confinement");
            DriveClientInstrumentedTest.run(); pass("Native Drive parser, pagination and simulated HTTP failures");
            testLaunch(context); pass("Activity launch, live updates, manga accessibility and recreation persistence");
            testMegaCatalog(context); pass("Persistent MEGA metadata, account isolation, atomic refresh and content-hash links");
            testLibraryUx(context); pass("MEGA library navigation, read filters, offline flags and dark/OLED themes");
            testStartup(context); pass("Configurable library/home/reader startup and unavailable/completed fallback");
            testUpdates(context); pass("Release metadata, ABI selection, URL/digest validation, APK identity and provider confinement");
            testRar(context); pass("Real RAR4/RAR5 import, image decode, cover, reader navigation and MEGA catalog visibility");
            results.putString("stream", "PASS: " + passed + " Android checks\n"); finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            results.putString("stream", "FAIL after " + passed + " checks\n" + trace); finish(Activity.RESULT_CANCELED, results);
        }
    }
    private void pass(String name) { passed++; Bundle status = new Bundle(); status.putString("stream", "PASS " + name + "\n"); sendStatus(0, status); }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    private static byte[] png(int color) throws IOException {
        return png(color, 120, 180);
    }
    private static byte[] png(int color, int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle(); return bytes.toByteArray();
    }
    private static byte[] comic() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"page10.png", "page2.png", "page1.png", "__MACOSX/._cover.png"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(png(name.equals("page1.png") ? Color.RED : Color.BLUE)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static Library.Book imported;
    private void testZipAndPdf(Context context) throws Exception {
        imported = Importer.fromStream(context, "Smoke test.cbz", new ByteArrayInputStream(comic()));
        try (PageSource source = new PageSource(Library.get(context).file(imported), "cbz")) {
            check(source.count() == 3, "Hidden resources must not become pages");
            Bitmap bitmap = source.render(0, 100, 180); check(bitmap.getPixel(30, 30) == Color.RED, "Natural page order"); bitmap.recycle();
            try { source.render(5, 100, 100); throw new AssertionError("Invalid index accepted"); } catch (IOException expected) { }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PdfDocument pdfDocument = new PdfDocument();
        try {
            PdfDocument.Page page = pdfDocument.startPage(new PdfDocument.PageInfo.Builder(200, 300, 1).create());
            page.getCanvas().drawColor(Color.GREEN); pdfDocument.finishPage(page); pdfDocument.writeTo(bytes);
        } finally { pdfDocument.close(); }
        File download = File.createTempFile("download-test-", ".pdf", context.getCacheDir());
        try (OutputStream output = new FileOutputStream(download)) { output.write(bytes.toByteArray()); }
        Library.Book pdf = Importer.fromDownload(context, "Smoke test.pdf", download);
        check(!download.exists() && Library.get(context).file(pdf).exists(), "A completed private download must move without a second copy");
        try (PageSource source = new PageSource(Library.get(context).file(pdf), "pdf")) {
            check(source.count() == 1, "PDF count"); Bitmap rendered = source.render(0, 400, 600);
            check(rendered.getPixel(50, 50) == Color.GREEN, "PDF rendering"); rendered.recycle();
        }
        try { Importer.fromStream(context, "bad.cbz", new ByteArrayInputStream(new byte[]{1, 2, 3})); throw new AssertionError("Broken ZIP accepted"); }
        catch (IOException expected) { }
        check(Library.get(context).books().size() == 2, "Failed import must leave the library unchanged");
        File outsideCache = File.createTempFile("outside-download-", ".pdf", context.getFilesDir());
        try {
            try { Importer.fromDownload(context, "wrong.pdf", outsideCache); throw new AssertionError("External move accepted"); }
            catch (IOException expected) { check(outsideCache.exists(), "Files outside the download cache must remain untouched"); }
        } finally { outsideCache.delete(); }
    }
    private void testStorage(Context context) throws Exception {
        Library library = Library.get(context); Progress before = library.state(imported.id());
        Progress saved = library.update(imported.id(), 1, false, true, new int[]{1});
        check(library.state(imported.id()).page == 1 && library.state(imported.id()).favorite, "Persist update");
        Progress conflict = new Progress(saved.id, 0, saved.pages, false, false, new int[0], saved.revision, saved.device);
        String otherId = "b".repeat(64);
        Progress other = new Progress(otherId, 0, 1, false, false, new int[0], 1, library.device);
        try { library.merge(List.of(other, conflict)); throw new AssertionError("Contradiction accepted"); } catch (IllegalArgumentException expected) { }
        check(library.state(otherId) == null && library.state(saved.id).page == 1, "Merge must roll back completely");
        Progress huge = new Progress(saved.id, 0, saved.pages, false, false, new int[0], 999_999_999_999L, library.device);
        try { library.merge(List.of(huge)); throw new AssertionError("Clock poisoning accepted"); } catch (IllegalArgumentException expected) { }
        library.update(saved.id, 2, false, true, new int[]{1}); check(library.state(saved.id).page == 2, "Still writable after failed merge");
        Progress wrongCount = new Progress(saved.id, 0, 4, false, false, new int[0], saved.revision + 10, library.device);
        try { library.merge(List.of(wrongCount)); throw new AssertionError("Remote page count accepted"); } catch (IllegalArgumentException expected) { }
        check(library.state(saved.id).pages == before.pages, "Actual page count remains authoritative");
        library.update(saved.id, 1, true, false, new int[]{0});
        library.updateReading(saved.id, 2, null, new int[]{2}, new int[0]);
        Progress reading = library.state(saved.id);
        check(reading.completed && !reading.favorite && reading.bookmarked(0) && reading.bookmarked(2), "Reading must preserve concurrent untouched fields");
        library.updateReading(saved.id, null, null, new int[0], new int[]{0});
        check(!library.state(saved.id).bookmarked(0) && library.state(saved.id).bookmarked(2), "A local bookmark removal must preserve unrelated additions");
        check(Progress.decode(Progress.encode(library.states())).size() == 2, "Export all states");
    }
    private void testSecrets(Context context) throws Exception {
        Secrets.put(context, "test.session", "not-a-real-session");
        check("not-a-real-session".equals(Secrets.get(context, "test.session")), "Session roundtrip");
        String stored = context.getSharedPreferences("secrets", 0).getString("test.session", "");
        check(!stored.contains("not-a-real"), "Session must be encrypted");
        Secrets.remove(context, "test.session"); check(Secrets.get(context, "test.session") == null, "Session cleared");
        Secrets.configure(context, "https://example.org/", "", "");
        check(Secrets.authorization(context).isEmpty(), "Anonymous WebDAV must remain anonymous after save");
        Secrets.configure(context, "https://example.org/", "tester", "not-a-real-password");
        check(Secrets.authorization(context).equals("Basic " + android.util.Base64.encodeToString("tester:not-a-real-password".getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)), "WebDAV credentials roundtrip");
        context.getSharedPreferences("cloud", 0).edit().clear().commit();
    }
    private void testJobConstraints(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        jobs.cancel(1); Cloud.schedule(context); check(jobs.getPendingJob(1) == null, "No background job before opt-in");
        context.getSharedPreferences("cloud", 0).edit().putString("provider", "webdav").putString("endpoint", "https://example.org/").putBoolean("auto", true).commit();
        try {
            Cloud.schedule(context); JobInfo job = jobs.getPendingJob(1);
            check(job != null && !job.isPeriodic() && job.isRequireBatteryNotLow(), "One-shot sync must require adequate battery");
            check(job.getNetworkType() == JobInfo.NETWORK_TYPE_UNMETERED && job.getMinLatencyMillis() == 30_000, "Network and batching policy");
        } finally { jobs.cancel(1); context.getSharedPreferences("cloud", 0).edit().clear().commit(); }
    }
    private void testDavParser() throws Exception {
        String xml = "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/comics/a.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        WebDav dav = new WebDav("https://example.org/comics/", "");
        check(WebDav.parseList(dav.root(), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)).size() == 1, "Native Android parser");
        try { dav.child(dav.root(), "../escape"); throw new AssertionError("Traversal accepted"); } catch (IOException expected) { }
    }
    private void testLaunch(Context context) throws Exception {
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        context.getSharedPreferences("library", 0).edit().putBoolean("grid", false).commit();
        Activity main = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync(); Thread.sleep(500);
        waitForIdleSync();
        boolean[] loaded = {false};
        for (int i = 0; i < 150 && !loaded[0]; i++) {
            runOnMainSync(() -> loaded[0] = loadedCovers(main.getWindow().getDecorView()) == 2);
            if (!loaded[0]) Thread.sleep(20);
        }
        check(loaded[0], "Visible library covers must decode");
        testCoverFailure(main);
        testLargeCovers(main);
        runOnMainSync(() -> findText(main.getWindow().getDecorView(), "Ver portadas").performClick());
        waitForIdleSync(); Thread.sleep(100);
        int[] columns = {0}; runOnMainSync(() -> columns[0] = findShelf(main.getWindow().getDecorView()).getNumColumns());
        check(columns[0] >= 2, "Cover view must use responsive columns");
        runOnMainSync(() -> {
            findText(main.getWindow().getDecorView(), "Buscar").performClick();
            ((android.widget.EditText) findText(main.getWindow().getDecorView(), "Buscar en tu biblioteca")).setText("Smoke test.pdf");
            check(findShelf(main.getWindow().getDecorView()).getCount() == 1, "Search must preserve library filtering");
            findText(main.getWindow().getDecorView(), "Buscar").performClick();
            check(findShelf(main.getWindow().getDecorView()).getCount() == 2, "Closing search must restore all books");
            findText(main.getWindow().getDecorView(), "Ver lista").performClick();
        });
        waitForIdleSync(); Thread.sleep(100);
        runOnMainSync(() -> columns[0] = findShelf(main.getWindow().getDecorView()).getNumColumns());
        check(columns[0] == 1, "List view must remain available");
        runOnMainSync(() -> main.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        waitForIdleSync(); Thread.sleep(300); check(!main.isDestroyed(), "Library rotation must retain its import executor");
        runOnMainSync(() -> main.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        waitForIdleSync(); Thread.sleep(300); runOnMainSync(main::finish); waitForIdleSync();
        Library library = Library.get(context);
        library.update(imported.id(), 0, false, true, new int[0]);
        context.getSharedPreferences("reader", 0).edit().putBoolean("manga", true).commit();
        Activity reader = startActivitySync(new Intent(context, ReaderActivity.class).putExtra("id", imported.id()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync(); waitForDescription(reader, "Página 1 de");
        setAccessiblePage(reader, 1); waitForDescription(reader, "Página 2 de");
        setAccessiblePage(reader, 0); waitForDescription(reader, "Página 1 de");
        runOnMainSync(() -> findPage(reader.getWindow().getDecorView()).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        waitForDescription(reader, "Página 2 de");
        ActivityMonitor monitor = addMonitor(ReaderActivity.class.getName(), null, false);
        runOnMainSync(reader::recreate);
        Activity recreated = waitForMonitorWithTimeout(monitor, 4000); removeMonitor(monitor);
        check(recreated != null, "Reader must recreate");
        waitForIdleSync(); waitForDescription(recreated, "Página 2 de");
        Thread.sleep(700); check(library.state(imported.id()).page == 1, "Recreation must preserve latest displayed page");
        library.update(imported.id(), 1, true, false, new int[]{0});
        runOnMainSync(() -> findText(recreated.getWindow().getDecorView(), "Siguiente").performClick());
        waitForDescription(recreated, "Página 3 de"); Thread.sleep(700);
        Progress state = library.state(imported.id());
        check(state.page == 2 && !state.favorite && state.completed && state.bookmarked(0), "An active reader must preserve remote fields it did not edit");
        runOnMainSync(recreated::finish); waitForIdleSync();
        context.getSharedPreferences("reader", 0).edit().putBoolean("manga", false).commit();
    }
    private PageView findPage(View view) {
        if (view instanceof PageView page) return page;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            PageView found = findPage(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private View findText(View view, String text) {
        if (view.getContentDescription() != null && text.contentEquals(view.getContentDescription())
                || view instanceof TextView label && text.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private android.widget.GridView findShelf(View view) {
        if (view instanceof android.widget.GridView shelf) return shelf;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.GridView found = findShelf(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private int loadedCovers(View view) {
        if (view instanceof CoverCache.CoverView cover && cover.getDrawable() instanceof android.graphics.drawable.BitmapDrawable image) {
            Bitmap bitmap = image.getBitmap();
            check(bitmap.getWidth() <= 192 && bitmap.getHeight() <= 288, "Covers must decode to thumbnail size");
            int pixel = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
            check(pixel == Color.RED || pixel == Color.GREEN, "A recycled cover must display the comic's first page");
            return 1;
        }
        int count = 0;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) count += loadedCovers(group.getChildAt(i));
        return count;
    }
    private void testCoverFailure(Activity activity) throws Exception {
        CoverCache[] cache = {null}; CoverCache.CoverView[] view = {null};
        try {
            runOnMainSync(() -> {
                cache[0] = new CoverCache(activity); view[0] = new CoverCache.CoverView(activity);
                cache[0].bind(view[0], new Library.Book("f".repeat(64), "Missing cover", "cbz", 0));
            });
            Thread.sleep(100);
            boolean[] placeholder = {false};
            runOnMainSync(() -> {
                placeholder[0] = view[0].getDrawable() != null && !(view[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                cache[0].bind(view[0], imported);
            });
            check(placeholder[0], "A cover error must keep a visible placeholder");
            boolean[] loaded = {false};
            for (int i = 0; i < 150 && !loaded[0]; i++) {
                runOnMainSync(() -> loaded[0] = loadedCovers(view[0]) == 1);
                if (!loaded[0]) Thread.sleep(20);
            }
            check(loaded[0], "A failed or recycled cover must not block the next book");
        } finally { runOnMainSync(() -> { if (cache[0] != null) cache[0].close(); }); }
    }
    private void testLargeCovers(Activity activity) throws Exception {
        for (int[] size : new int[][]{{1200, 1800}, {4096, 128}}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("cover.png")); zip.write(png(Color.RED, size[0], size[1])); zip.closeEntry();
            }
            Library.Book book = Importer.fromStream(getTargetContext(), "Thumbnail size test.cbz", new ByteArrayInputStream(bytes.toByteArray()));
            CoverCache[] cache = {null}; CoverCache.CoverView[] view = {null}; int[] decoded = {0, 0};
            try {
                runOnMainSync(() -> { cache[0] = new CoverCache(activity); view[0] = new CoverCache.CoverView(activity); cache[0].bind(view[0], book); });
                for (int i = 0; i < 150 && decoded[0] == 0; i++) {
                    runOnMainSync(() -> {
                        if (view[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable image) {
                            decoded[0] = image.getBitmap().getWidth(); decoded[1] = image.getBitmap().getHeight();
                        }
                    });
                    if (decoded[0] == 0) Thread.sleep(20);
                }
                check(decoded[0] > 0 && decoded[0] <= 192 && decoded[1] <= 288,
                        "Large/panoramic cover exceeds thumbnail budget: " + decoded[0] + "x" + decoded[1]);
            } finally {
                runOnMainSync(() -> { if (cache[0] != null) cache[0].close(); });
                Library.get(getTargetContext()).remove(book);
            }
        }
    }
    private void setAccessiblePage(Activity reader, int page) {
        Bundle arguments = new Bundle(); arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, page);
        runOnMainSync(() -> findText(reader.getWindow().getDecorView(), "Ir a una página")
                .performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments));
    }
    private static final String CATALOG_ACCOUNT = "fixture-owner";
    private void testMegaCatalog(Context context) throws Exception {
        MegaLibrary catalog = MegaLibrary.get(context);
        List<MegaClient.Node> nodes = List.of(new MegaClient.Node(101, "Serie A", true, -1),
                new MegaClient.Node(102, "Leído.cbz", false, 10), new MegaClient.Node(103, "Pendiente.pdf", false, 20),
                new MegaClient.Node(104, "Notas.txt", false, 3));
        catalog.replace(CATALOG_ACCOUNT, 100, "Mi colección", nodes);
        check(catalog.snapshot(CATALOG_ACCOUNT, 100).entries().size() == 3, "Only supported files and directories enter the library");
        check(catalog.snapshot("another-owner", 100) == null, "Same handles must not cross accounts");
        catalog.replace("another-owner", 100, "Other", List.of());
        check(catalog.snapshot("another-owner", 100).entries().isEmpty(), "Empty fetched folder differs from missing metadata");
        try { catalog.replace(CATALOG_ACCOUNT, 100, "Broken", List.of(nodes.get(1), nodes.get(1))); throw new AssertionError("Duplicate remote handles accepted"); }
        catch (IllegalArgumentException expected) { }
        check(catalog.snapshot(CATALOG_ACCOUNT, 100).name().equals("Mi colección") && catalog.snapshot(CATALOG_ACCOUNT, 100).entries().size() == 3, "Invalid refresh preserves the old snapshot atomically");
        catalog.link(context, CATALOG_ACCOUNT, 102, imported);
        catalog.replace(CATALOG_ACCOUNT, 100, "Mi colección", nodes);
        MegaLibrary.Entry linked = catalog.snapshot(CATALOG_ACCOUNT, 100).entries().get(1);
        check(imported.id().equals(linked.bookId()), "Metadata refresh must preserve content-hash mapping");
        catalog.flags(context, CATALOG_ACCOUNT, linked, false, null);
        check(Library.get(context).state(imported.id()).page == 0 && !Library.get(context).state(imported.id()).completed, "Mark unread resets reading position");
        catalog.flags(context, CATALOG_ACCOUNT, linked, true, null);
        MegaLibrary.Entry pending = catalog.snapshot(CATALOG_ACCOUNT, 100).entries().get(2);
        catalog.flags(context, CATALOG_ACCOUNT, pending, true, true);
        catalog.link(context, CATALOG_ACCOUNT, 103, imported);
        check(Library.get(context).state(imported.id()).favorite, "Pre-download flags transfer to hash progress");
        catalog.replace(CATALOG_ACCOUNT, 101, "Serie A", List.of(new MegaClient.Node(105, "Capítulo 1.cbz", false, 10)));
        catalog.replace(CATALOG_ACCOUNT, 100, "Mi colección", List.of(nodes.get(0), nodes.get(1), new MegaClient.Node(106, "Sin leer.pdf", false, 20)));
        check(catalog.snapshot(CATALOG_ACCOUNT, 100).entries().size() == 3 && catalog.snapshot(CATALOG_ACCOUNT, 101).entries().size() == 1, "Refresh removes deleted children without removing cached subfolders");
    }
    private Activity launchMain(Context context) throws Exception {
        Activity main = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync(); Thread.sleep(200); return main;
    }
    private void waitCount(Activity activity, int count) throws Exception {
        for (int i = 0; i < 150; i++) {
            int[] actual = {-1}; runOnMainSync(() -> { GridView shelf = findShelf(activity.getWindow().getDecorView()); if (shelf != null) actual[0] = shelf.getCount(); });
            if (actual[0] == count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Library did not show " + count + " entries");
    }
    private void clickAccessible(String text) throws Exception {
        for (int i = 0; i < 100; i++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo node = accessible(root, text);
            while (node != null) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { waitForIdleSync(); return; }
                node = node.getParent();
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Accessible control absent: " + text);
    }
    private AccessibilityNodeInfo accessible(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString()) || text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = accessible(node.getChild(i), text); if (found != null) return found; }
        return null;
    }
    private void filter(String text) throws Exception { clickAccessible("Filtrar biblioteca"); clickAccessible(text); }
    private Activity chooseTheme(Activity current, String text) throws Exception {
        clickAccessible("Ajustes"); clickAccessible("Tema");
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        clickAccessible(text); Activity next = waitForMonitorWithTimeout(monitor, 4000); removeMonitor(monitor);
        check(next != null && current.isDestroyed(), "Theme must recreate the actual activity"); waitForIdleSync(); return next;
    }
    private void testLibraryUx(Context context) throws Exception {
        context.getSharedPreferences("appearance", 0).edit().clear().commit();
        context.getSharedPreferences("library", 0).edit().clear().putBoolean("grid", true).commit();
        Activity main = launchMain(context); waitCount(main, 2);
        Activity rootMain = main;
        MegaLibrary.Snapshot fixture = MegaLibrary.get(context).snapshot(CATALOG_ACCOUNT, 100);
        runOnMainSync(() -> ((MainActivity)rootMain).showMegaFolderPicker(MegaClient.instance(context), 100, "Mi colección", new ArrayDeque<>(),
                new MainActivity.FolderListing(CATALOG_ACCOUNT, fixture.entries().stream().map(MegaLibrary.Entry::node).toList())));
        clickAccessible("Aceptar"); waitCount(main, 3);
        check(CATALOG_ACCOUNT.equals(context.getSharedPreferences("library", 0).getString("mega_account", "")), "Accept must actually persist the chosen folder and owner");
        boolean[] present = {false}; runOnMainSync(() -> present[0] = findText(rootMain.getWindow().getDecorView(), "MEGA · Mi colección") != null);
        check(present[0], "Default startup must be the saved folder library");
        filter("Leídos"); waitCount(main, 2);
        filter("Sin leer"); waitCount(main, 2);
        filter("Todos los cómics"); waitCount(main, 3);
        runOnMainSync(() -> { GridView shelf = findShelf(rootMain.getWindow().getDecorView()); shelf.performItemClick(shelf.getChildAt(0), 0, 0); });
        waitCount(main, 1);
        runOnMainSync(() -> rootMain.onBackPressed()); waitCount(main, 3);
        runOnMainSync(() -> { GridView shelf = findShelf(rootMain.getWindow().getDecorView()); shelf.getOnItemLongClickListener().onItemLongClick(shelf, shelf.getChildAt(2), 2, 2); });
        clickAccessible("Marcar leído"); Thread.sleep(200); filter("Leídos"); waitCount(main, 3);
        main = chooseTheme(main, "Oscuro"); waitCount(main, 3);
        Activity dark = main; int[] background = {0};
        runOnMainSync(() -> background[0] = ((android.graphics.drawable.ColorDrawable)((android.view.ViewGroup)dark.findViewById(android.R.id.content)).getChildAt(0).getBackground()).getColor());
        check(background[0] == 0xff121212, "Dark library must render a dark background");
        main = chooseTheme(main, "Negro OLED"); waitCount(main, 3);
        Activity oled = main;
        runOnMainSync(() -> background[0] = ((android.graphics.drawable.ColorDrawable)((android.view.ViewGroup)oled.findViewById(android.R.id.content)).getChildAt(0).getBackground()).getColor());
        check(background[0] == Color.BLACK, "OLED must render real black");
        runOnMainSync(main::finish); waitForIdleSync();
        main = launchMain(context); waitCount(main, 3); // A new instance must recover folder and reading flags.
        runOnMainSync(main::finish); waitForIdleSync();
    }
    private void testStartup(Context context) throws Exception {
        Activity main = launchMain(context);
        clickAccessible("Ajustes"); clickAccessible("Pantalla de inicio"); clickAccessible("Conexiones y configuración");
        runOnMainSync(main::finish); waitForIdleSync();
        main = launchMain(context); Activity home = main; boolean[] setup = {false};
        runOnMainSync(() -> setup[0] = findText(home.getWindow().getDecorView(), "Importar archivos") != null);
        check(setup[0], "Startup can open the original setup surface");
        clickAccessible("Ajustes"); clickAccessible("Pantalla de inicio"); clickAccessible("Último cómic en curso");
        runOnMainSync(main::finish); waitForIdleSync();
        Library library = Library.get(context); library.update(imported.id(), 1, false, true, new int[0]);
        ActivityMonitor monitor = addMonitor(ReaderActivity.class.getName(), null, false);
        main = launchMain(context); Activity reader = waitForMonitorWithTimeout(monitor, 4000); removeMonitor(monitor);
        check(reader != null, "Reader startup must open the actual last unfinished comic"); waitForDescription(reader, "Página 2 de");
        boolean[] white = {false}; Activity openReader = reader;
        runOnMainSync(() -> white[0] = ((TextView)findText(openReader.getWindow().getDecorView(), imported.title())).getCurrentTextColor() == 0xffeeeeee);
        check(white[0], "OLED reader controls need light text");
        runOnMainSync(reader::finish); waitForIdleSync(); runOnMainSync(main::finish); waitForIdleSync();
        library.update(imported.id(), 1, true, true, new int[0]);
        main = launchMain(context); waitCount(main, 3); runOnMainSync(main::finish); waitForIdleSync();
        context.getSharedPreferences("reader", 0).edit().putString("last_book", "missing").commit();
        main = launchMain(context); waitCount(main, 3); runOnMainSync(main::finish); waitForIdleSync();
        context.getSharedPreferences("appearance", 0).edit().putString("startup", "library").commit();
    }
    private void waitForDescription(Activity activity, String prefix) throws Exception {
        for (int i = 0; i < 150; i++) {
            boolean[] found = {false};
            runOnMainSync(() -> {
                PageView page = findPage(activity.getWindow().getDecorView());
                found[0] = page != null && page.getContentDescription().toString().startsWith(prefix);
            });
            if (found[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Page did not appear: " + prefix);
    }
    private org.json.JSONObject updateAsset(String name) throws Exception {
        return new org.json.JSONObject().put("name", name).put("state", "uploaded").put("size", 10)
                .put("digest", "sha256:" + "0".repeat(64)).put("browser_download_url", ReleaseInfo.REPOSITORY + "/releases/download/v0.5.0/" + name);
    }
    private void testRar(Context context) throws Exception {
        Library library = Library.get(context);
        for (String fixture : new String[]{"rar4-stored.cbr", "rar5-solid.cbr"}) {
            File download = File.createTempFile("cbr-", ".part", context.getCacheDir());
            try (InputStream input = getContext().getAssets().open("comics/" + fixture); OutputStream output = new FileOutputStream(download)) {
                ComicFiles.copy(input, output, ComicFiles.MAX_COMIC);
            }
            Library.Book book = Importer.fromDownload(context, fixture, download);
            try {
                check(book.format().equals("cbr") && !download.exists(), "RAR download must move into the library as cbr");
                try (PageSource pages = new PageSource(library.file(book), book.format())) {
                    check(pages.count() == 3, "Ignore hidden RAR resources");
                    int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
                    for (int page : new int[]{0, 2, 1, 0}) {
                        Bitmap bitmap = pages.render(page, 120, 180);
                        check(bitmap.getPixel(30, 30) == colors[page], "RAR decode/natural order after seek"); bitmap.recycle();
                    }
                }
                MegaLibrary catalog = MegaLibrary.get(context);
                catalog.replace("rar-owner", 500, "CBR", List.of(new MegaClient.Node(501, "Example.CBR", false, book.size()), new MegaClient.Node(502, "Example.RAR", false, book.size())));
                check(catalog.snapshot("rar-owner", 500).entries().size() == 2, "MEGA must keep both CBR and RAR files");
                catalog.link(context, "rar-owner", 501, book);
                check(book.id().equals(catalog.snapshot("rar-owner", 500).entries().get(0).bookId()), "MEGA links downloaded RAR to content progress");
                Activity reader = startActivitySync(new Intent(context, ReaderActivity.class).putExtra("id", book.id()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    waitForIdleSync(); waitForDescription(reader, "Página 1 de");
                    setAccessiblePage(reader, 2); waitForDescription(reader, "Página 3 de");
                    setAccessiblePage(reader, 0); waitForDescription(reader, "Página 1 de");
                    CoverCache[] cache = {null}; CoverCache.CoverView[] cover = {null}; boolean[] loaded = {false};
                    try {
                        runOnMainSync(() -> { cache[0] = new CoverCache(reader); cover[0] = new CoverCache.CoverView(reader); cache[0].bind(cover[0], book); });
                        for (int i = 0; i < 150 && !loaded[0]; i++) {
                            runOnMainSync(() -> loaded[0] = loadedCovers(cover[0]) == 1); if (!loaded[0]) Thread.sleep(20);
                        }
                        check(loaded[0], "CBR cover must decode through the normal cache");
                    } finally { runOnMainSync(() -> { if (cache[0] != null) cache[0].close(); }); }
                } finally { runOnMainSync(reader::finish); waitForIdleSync(); }
            } finally {
                // Keep one CBR beside the base CBZ/PDF fixtures for manual release verification.
                if (fixture.equals("rar4-stored.cbr")) library.remove(book);
            }
        }
    }
    private void testUpdates(Context context) throws Exception {
        org.json.JSONObject arm = updateAsset("ComicReader-arm64-v8a.apk"), x86 = updateAsset("ComicReader-x86_64.apk");
        org.json.JSONObject release = new org.json.JSONObject().put("tag_name", "v0.5.0").put("draft", false).put("prerelease", false)
                .put("assets", new org.json.JSONArray().put(arm).put(x86));
        check(ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a", "x86_64"}).asset().equals("ComicReader-arm64-v8a.apk"), "Prefer native ARM64");
        check(ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"x86_64", "arm64-v8a"}).asset().equals("ComicReader-x86_64.apk"), "Respect ordered device ABIs");
        check(ReleaseInfo.parse(release.toString(), "0.5.0", new String[]{"arm64-v8a"}) == null, "Never propose same version");
        release.put("prerelease", true); check(ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}) == null, "Ignore prereleases"); release.put("prerelease", false);
        for (String digest : new String[]{"", "sha256:bad"}) {
            arm.put("digest", digest);
            try { ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}); throw new AssertionError("Invalid digest accepted"); } catch (IOException expected) { }
        }
        arm.put("digest", "sha256:" + "0".repeat(64)).put("browser_download_url", "https://github.com/other/project/update.apk");
        try { ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}); throw new AssertionError("Foreign repository asset accepted"); } catch (IOException expected) { }
        arm = updateAsset("ComicReader-arm64-v8a.apk");
        release.put("assets", new org.json.JSONArray().put(arm).put(arm));
        try { ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}); throw new AssertionError("Duplicate asset accepted"); } catch (IOException expected) { }
        release.put("assets", new org.json.JSONArray().put(updateAsset("ComicReader-universal.apk")));
        check(ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}).asset().equals("ComicReader-universal.apk"), "Explicit universal fallback");
        release.put("assets", new org.json.JSONArray().put(updateAsset("ComicReader-arm64-v8a.apk").put("size", ReleaseInfo.MAX_APK + 1)));
        try { ReleaseInfo.parse(release.toString(), "0.4.0", new String[]{"arm64-v8a"}); throw new AssertionError("Oversized APK accepted"); } catch (IOException expected) { }
        File ready = UpdateProvider.apk(context); ready.getParentFile().mkdirs();
        try {
            try (OutputStream output = new FileOutputStream(ready)) { output.write(new byte[]{1,2,3}); }
            try { Updater.verify(context, ready, "0.5.0", "0".repeat(64), 3); throw new AssertionError("Corrupt APK digest accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("SHA-256"), "Must reject digest before archive parsing"); }
            try (InputStream input = context.getContentResolver().openInputStream(UpdateProvider.URI)) { check(input.read() == 1, "Installer URI reads only the fixed cache file"); }
            android.net.Uri outside = android.net.Uri.parse("content://org.comicreader.app.updates/../files/library.db");
            check(context.getContentResolver().getType(outside) == null, "No content type outside the APK URI");
            try { context.getContentResolver().openFileDescriptor(outside, "r"); throw new AssertionError("Provider path traversal accepted"); }
            catch (IllegalArgumentException | FileNotFoundException expected) { }
            try { context.getContentResolver().openFileDescriptor(UpdateProvider.URI, "w"); throw new AssertionError("Writable installer grant accepted"); }
            catch (FileNotFoundException expected) { }
            android.content.pm.ProviderInfo provider = context.getPackageManager().getProviderInfo(new ComponentName(context, UpdateProvider.class), 0);
            check(!provider.exported && provider.grantUriPermissions, "Provider must be private with per-URI grants");
        } finally { check(ready.delete(), "Remove test update bytes"); }
    }
}
