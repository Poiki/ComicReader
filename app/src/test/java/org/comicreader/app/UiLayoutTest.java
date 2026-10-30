package org.comicreader.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.*;
import android.view.View;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiLayoutTest {
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void create() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        activity.getSharedPreferences("appearance", 0).edit().putString("theme", "oled").commit();
        Ui.applyTheme(activity);
    }

    @After
    public void close() {
        controller.pause().stop().destroy();
    }

    private Rect ink(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        Rect bounds = new Rect(width, height, 0, 0);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) > 200
                        && Color.red(pixel) > 200
                        && Color.green(pixel) > 200
                        && Color.blue(pixel) > 200) {
                    bounds.left = Math.min(bounds.left, x);
                    bounds.top = Math.min(bounds.top, y);
                    bounds.right = Math.max(bounds.right, x + 1);
                    bounds.bottom = Math.max(bounds.bottom, y + 1);
                }
            }
        bitmap.recycle();
        assertFalse("The icon must actually paint", bounds.isEmpty());
        return bounds;
    }

    @Test
    public void iconStaysCenteredInToolbarAndPrimaryButtons() {
        for (int[] size : new int[][] {{48, 56}, {56, 56}, {72, 48}}) {
            View button = Ui.icon(activity, R.drawable.icon_plus, "Add", () -> {});
            Rect painted = ink(button, size[0], size[1]);
            assertEquals(
                    "Horizontal icon center in " + size[0] + "x" + size[1],
                    size[0] / 2f,
                    painted.exactCenterX(),
                    1f);
            assertEquals("Vertical icon center", size[1] / 2f, painted.exactCenterY(), 1f);
        }
    }

    @Test
    public void primaryIconUsesTheAvailableSpace() {
        View button = Ui.icon(activity, R.drawable.icon_plus, "Add", () -> {});
        Rect normal = ink(button, 48, 48);
        assertTrue("Toolbar glyph is too small: " + normal.width(), normal.width() >= 18);
        Rect primary = ink(button, 56, 56);
        assertTrue(
                "Primary glyph must grow with its button: " + primary.width(),
                primary.width() >= 23);
    }
}
