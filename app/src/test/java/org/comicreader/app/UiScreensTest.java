package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiScreensTest {
    private static void layout(View root, int width, int height) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static View find(View root, String description) {
        if (description.equals(String.valueOf(root.getContentDescription()))) return root;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        return null;
    }

    private static <T extends View> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = first(group.getChildAt(i), type);
                if (found != null) return found;
            }
        return null;
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Background UI work did not finish");
    }

    private static void checkButtons(View root) {
        if (root.getVisibility() != View.VISIBLE) return;
        if (root instanceof ImageButton button && button.getWidth() > 0) {
            RectF image = new RectF(button.getDrawable().getBounds());
            button.getImageMatrix().mapRect(image);
            image.offset(button.getPaddingLeft(), button.getPaddingTop());
            assertEquals(
                    button.getContentDescription().toString(),
                    button.getWidth() / 2f,
                    image.centerX(),
                    1f);
            assertEquals(
                    button.getContentDescription().toString(),
                    button.getHeight() / 2f,
                    image.centerY(),
                    1f);
            assertTrue(
                    image.left >= -1
                            && image.top >= -1
                            && image.right <= button.getWidth() + 1
                            && image.bottom <= button.getHeight() + 1);
            View parent = (View) button.getParent();
            assertTrue(
                    "Image buttons fit their container",
                    button.getLeft() >= 0
                            && button.getTop() >= 0
                            && button.getRight() <= parent.getWidth()
                            && button.getBottom() <= parent.getHeight());
        }
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) checkButtons(group.getChildAt(i));
    }

    private static void screenshot(View root, int width, int height, String name) throws Exception {
        layout(root, width, height);
        checkButtons(root);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File directory = new File("build/reports/ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (OutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private static Library.Book fixture(Context context, int number) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(192, 288, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(25 + number * 17, 50 + number * 9, 110 + number * 6));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xffdde7ed);
        canvas.drawRect(12, 12, 180, 124, paint);
        paint.setColor(0xff172333);
        canvas.drawRect(12, 136, 92, 276, paint);
        canvas.drawRect(104, 136, 180, 276, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(20);
        canvas.drawText("Volume " + number, 20, 230, paint);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        bitmap.recycle();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("page1.png"));
            zip.write(png.toByteArray());
            zip.closeEntry();
        }
        byte[] bytes = archive.toByteArray();
        String id =
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        Library.Book book = new Library.Book(id, "Volume " + number + ".cbz", "cbz", bytes.length);
        Library library = Library.get(context);
        // Seed a closed archive: Windows cannot rename the open ZIP used by Android's importer.
        File staged = library.staging();
        java.nio.file.Files.write(staged.toPath(), bytes);
        library.add(book, staged, 1);
        return book;
    }

    @Test
    public void libraryReaderAndSettingsKeepAlignedControls() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", 0)
                .edit()
                .putString("theme", "oled")
                .putString("covers", "off")
                .commit();
        context.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit();
        Library.Book book = null;
        for (int i = 1; i <= 9; i++) book = fixture(context, i);
        for (String configuration :
                new String[] {
                    "phone",
                    "small-phone",
                    "tablet",
                    "large-font",
                    "light",
                    "dark",
                    "spanish",
                    "spanish-large-font"
                }) {
            int width =
                    configuration.equals("tablet")
                            ? 1280
                            : configuration.equals("small-phone") ? 320 : 411;
            int height =
                    configuration.equals("tablet")
                            ? 800
                            : configuration.equals("small-phone") ? 640 : 891;
            RuntimeEnvironment.setQualifiers(
                    (configuration.startsWith("spanish") ? "es-rES-" : "en-rUS-")
                            + "w"
                            + width
                            + "dp-h"
                            + height
                            + "dp-mdpi");
            RuntimeEnvironment.setFontScale(configuration.endsWith("large-font") ? 1.7f : 1f);
            context.getSharedPreferences("appearance", 0)
                    .edit()
                    .putString(
                            "theme",
                            configuration.equals("light")
                                    ? "light"
                                    : configuration.equals("dark") ? "dark" : "oled")
                    .commit();
            try (ActivityController<MainActivity> main =
                    Robolectric.buildActivity(MainActivity.class).setup().visible()) {
                View root = main.get().getWindow().getDecorView();
                layout(root, width, height);
                GridView grid = first(root, GridView.class);
                waitFor(() -> grid.getCount() == 9);
                layout(root, width, height);
                waitFor(
                        () ->
                                first(grid, CoverCache.CoverView.class).getDrawable()
                                        instanceof BitmapDrawable);
                screenshot(root, width, height, "library-" + configuration);
                View source =
                        find(
                                root,
                                main.get().getString(R.string.source_local)
                                        + ". "
                                        + main.get().getString(R.string.source_device));
                ViewGroup sources = (ViewGroup) source.getParent();
                for (int i = 0; i < sources.getChildCount(); i++)
                    assertEquals(
                            "Provider cards retain equal heights",
                            source.getHeight(),
                            sources.getChildAt(i).getHeight());
                View tile = grid.getChildAt(0);
                assertNotNull("Overflow has an expanded touch area", tile.getTouchDelegate());
                TouchDelegate delegate = tile.getTouchDelegate();
                tile.requestLayout();
                layout(root, width, height);
                assertSame(
                        "Unchanged geometry reuses its touch delegate",
                        delegate,
                        tile.getTouchDelegate());
                Rect target =
                        tile.getTouchDelegate().getTouchDelegateInfo().getRegionAt(0).getBounds();
                assertTrue(target.width() >= 48 && target.height() >= 48);
                for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent touch =
                            MotionEvent.obtain(0, 10, action, target.left + 1, target.top + 1, 0);
                    tile.dispatchTouchEvent(touch);
                    touch.recycle();
                }
                shadowOf(Looper.getMainLooper()).idle();
                Dialog options = ShadowDialog.getLatestDialog();
                assertNotNull("Expanded overflow target opens its menu", options);
                assertTrue(options.isShowing());
                options.dismiss();
                find(root, main.get().getString(R.string.settings_title)).performClick();
                Dialog settings = ShadowDialog.getLatestDialog();
                screenshot(
                        settings.getWindow().getDecorView(),
                        width,
                        height,
                        "settings-" + configuration);
                settings.dismiss();
            }
            Intent intent = new Intent(context, ReaderActivity.class).putExtra("id", book.id());
            try (ActivityController<ReaderActivity> reader =
                    Robolectric.buildActivity(ReaderActivity.class, intent).setup().visible()) {
                View root = reader.get().getWindow().getDecorView();
                layout(root, width, height);
                PageView page = first(root, PageView.class);
                String description =
                        reader.get()
                                .getString(R.string.reader_page_description, 1, 1, book.title());
                waitFor(() -> page.getContentDescription().toString().equals(description));
                screenshot(root, width, height, "reader-" + configuration);
                View direction = find(root, reader.get().getString(R.string.reader_direction));
                ViewGroup tools = (ViewGroup) direction.getParent();
                View next = find(tools, reader.get().getString(R.string.action_next));
                Rect expected = new Rect(0, 0, next.getWidth(), next.getHeight());
                tools.offsetDescendantRectToMyCoords(next, expected);
                for (int i : new int[] {0, 1, 4, 5}) {
                    ImageView icon = first(tools.getChildAt(i), ImageView.class);
                    Rect actual = new Rect(0, 0, icon.getWidth(), icon.getHeight());
                    tools.offsetDescendantRectToMyCoords(icon, actual);
                    assertEquals(
                            "Reader glyphs share a horizontal baseline",
                            expected.exactCenterY(),
                            actual.exactCenterY(),
                            1f);
                }
            }
        }
    }
}
