package org.comicreader.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-w411dp-h891dp-mdpi")
public final class FdroidTest {
    private Context context;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        WidgetTest.reset(context);
        context.getSharedPreferences("appearance", 0).edit().putString("covers", "off").commit();
    }

    @After
    public void cleanup() throws Exception {
        WidgetTest.reset(context);
    }

    private static String text(View view) {
        StringBuilder output = new StringBuilder();
        if (view instanceof TextView label) output.append(label.getText()).append('\n');
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++)
                output.append(text(group.getChildAt(i)));
        return output.toString();
    }

    @Test
    public void googleCodeAndApkInstallerAreAbsentFromTheVariant() throws Exception {
        assertFalse(BuildConfig.GOOGLE_SERVICES);
        assertFalse(BuildConfig.EXTERNAL_UPDATES);
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.google.android.gms.auth.api.identity.Identity"));
        PackageInfo info =
                context.getPackageManager()
                        .getPackageInfo(
                                context.getPackageName(),
                                PackageManager.GET_PERMISSIONS | PackageManager.GET_PROVIDERS);
        assertFalse(
                Arrays.asList(info.requestedPermissions)
                        .contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        if (info.providers != null)
            for (android.content.pm.ProviderInfo provider : info.providers)
                assertNotEquals(UpdateProvider.class.getName(), provider.name);
    }

    @Test
    public void unsupportedDriveRequestsFailWithoutSchedulingBackgroundWork() throws Exception {
        context.getSharedPreferences("cloud", 0)
                .edit()
                .putString("provider", "drive")
                .putBoolean("auto", true)
                .commit();
        assertFalse(Cloud.configured(context));
        Cloud.schedule(context);
        assertTrue(
                context.getSystemService(android.app.job.JobScheduler.class)
                        .getAllPendingJobs()
                        .isEmpty());
        IOException unavailable = assertThrows(IOException.class, () -> DriveAuth.silent(context));
        assertEquals(
                context.getString(R.string.fdroid_drive_unavailable), unavailable.getMessage());
        try (ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            DriveAuth.signIn(main.get(), token -> fail("Unexpected token"), failure::set);
            assertEquals(unavailable.getMessage(), failure.get().getMessage());
            assertNull(shadowOf(main.get()).peekNextStartedActivity());
        }
    }

    @Test
    public void appExposesOnlyAvailableProvidersAndDelegatesUpdatesToFdroid() throws Exception {
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity main = controller.get();
            assertNull(ReflectionHelpers.getField(main, "updater"));
            ViewGroup sources = ReflectionHelpers.getField(main, "sourceCards");
            assertEquals(3, sources.getChildCount());
            String providers = text(sources);
            assertTrue(providers.contains("MEGA"));
            assertTrue(providers.contains("WebDAV"));
            assertFalse(providers.contains("Drive"));
            ReflectionHelpers.callInstanceMethod(main, "cloudMenu");
            android.app.AlertDialog cloud =
                    (android.app.AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(3, cloud.getListView().getCount());
            assertEquals(
                    main.getString(R.string.ui_mega_direct_access),
                    cloud.getListView().getAdapter().getItem(0));
            cloud.dismiss();
            ReflectionHelpers.callInstanceMethod(main, "more");
            Dialog settings = ReflectionHelpers.getField(main, "settingsDialog");
            String labels = text(settings.getWindow().getDecorView());
            assertTrue(labels.contains(main.getString(R.string.fdroid_updates)));
            assertTrue(labels.contains(main.getString(R.string.support_title)));
            assertFalse(labels.contains("GitHub Releases"));
            assertFalse(labels.contains(main.getString(R.string.settings_auto_updates)));
            assertTrue(main.getString(R.string.about_message, "Ready").contains("F-Droid"));
        }
    }
}
