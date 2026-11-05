package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SupportTest {
    @Test
    public void koFiOnlyOpensAfterTheUserChoosesIt() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Ui.applyTheme(activity);
            Support.show(activity);
            UiScreensTest.screenshot(
                    ShadowDialog.getLatestDialog().getWindow().getDecorView(),
                    411,
                    340,
                    "support-oled");
            assertNull(shadowOf(activity).peekNextStartedActivity());
            ((AlertDialog) ShadowDialog.getLatestDialog())
                    .getButton(AlertDialog.BUTTON_NEGATIVE)
                    .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(activity).peekNextStartedActivity());
            Support.show(activity);
            ((AlertDialog) ShadowDialog.getLatestDialog())
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, intent.getAction());
            assertEquals("https://ko-fi.com/A0A71AC06Z", intent.getDataString());
        }
    }

    public static final class NoBrowser extends Activity {
        @Override
        public void startActivity(Intent intent) {
            throw new ActivityNotFoundException();
        }
    }

    @Test
    public void aMissingBrowserShowsALocalizedMessage() {
        try (var controller = Robolectric.buildActivity(NoBrowser.class).setup()) {
            Activity activity = controller.get();
            Ui.applyTheme(activity);
            Support.show(activity);
            ((AlertDialog) ShadowDialog.getLatestDialog())
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(
                    activity.getString(R.string.support_no_browser),
                    ShadowToast.getTextOfLatestToast());
        }
    }
}
