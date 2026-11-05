package org.comicreader.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** No Google runtime is bundled; saved Drive links fail explicitly instead of opening login. */
public final class DriveAuth {
    public static final int REQUEST = 411;
    static final String READ = "https://www.googleapis.com/auth/drive.readonly";
    static final String STATE = "https://www.googleapis.com/auth/drive.appdata";

    private DriveAuth() {}

    public static void signIn(
            Activity activity, Consumer<String> onToken, Consumer<Throwable> onFailure) {
        onFailure.accept(unavailable(activity));
    }

    public static String silent(Context context) throws IOException {
        throw unavailable(context);
    }

    public static void disconnect(
            Activity activity, Consumer<Void> done, Consumer<Throwable> fail) {
        activity.getSharedPreferences("drive", 0).edit().clear().apply();
        done.accept(null);
    }

    public static boolean onActivityResult(
            Activity activity,
            int request,
            int result,
            Intent data,
            Consumer<String> onToken,
            Consumer<Throwable> onFailure) {
        if (request != REQUEST) return false;
        onFailure.accept(unavailable(activity));
        return true;
    }

    static String requireAuthorization(String token, List<String> granted) throws IOException {
        if (granted == null || !granted.contains(READ) || !granted.contains(STATE))
            throw new IOException(
                    "Drive needs read access and app data access; reconnect and grant both");
        DriveClient.requireToken(token);
        return token;
    }

    private static IOException unavailable(Context context) {
        return new IOException(context.getString(R.string.fdroid_drive_unavailable));
    }
}
