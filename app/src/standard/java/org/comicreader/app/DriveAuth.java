package org.comicreader.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** GIS owns the account session; the app receives an access token only in memory. */
public final class DriveAuth {
    public static final int REQUEST = 411;
    static final String READ = "https://www.googleapis.com/auth/drive.readonly";
    static final String STATE = "https://www.googleapis.com/auth/drive.appdata";

    private DriveAuth() {}

    private static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(List.of(new Scope(READ), new Scope(STATE)))
                .build();
    }

    public static void signIn(
            Activity activity, Consumer<String> onToken, Consumer<Throwable> onFailure) {
        try {
            Identity.getAuthorizationClient(activity)
                    .authorize(request())
                    .addOnSuccessListener(
                            activity,
                            result -> {
                                if (result.hasResolution()) {
                                    try {
                                        PendingIntent pending = result.getPendingIntent();
                                        if (pending == null)
                                            throw new IOException(
                                                    "Google returned no authorization dialog");
                                        activity.startIntentSenderForResult(
                                                pending.getIntentSender(), REQUEST, null, 0, 0, 0);
                                    } catch (Exception e) {
                                        onFailure.accept(e);
                                    }
                                } else deliver(result, onToken, onFailure);
                            })
                    .addOnFailureListener(activity, onFailure::accept);
        } catch (RuntimeException e) {
            onFailure.accept(e);
        }
    }

    /** Background jobs may obtain a fresh token, but never display an account or consent dialog. */
    public static String silent(Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Silent authorization requires a worker thread");
        AuthorizationResult result =
                Tasks.await(
                        Identity.getAuthorizationClient(context).authorize(request()),
                        30,
                        TimeUnit.SECONDS);
        if (result.hasResolution())
            throw new IOException("Open the app and sign in to Google Drive");
        return requireAuthorization(result.getAccessToken(), result.getGrantedScopes());
    }

    public static void disconnect(
            Activity activity, Consumer<Void> done, Consumer<Throwable> fail) {
        new Thread(
                        () -> {
                            try {
                                String token = silent(activity);
                                new DriveClient(token).revoke();
                                Tasks.await(
                                        Identity.getAuthorizationClient(activity)
                                                .clearToken(
                                                        ClearTokenRequest.builder()
                                                                .setToken(token)
                                                                .build()),
                                        30,
                                        TimeUnit.SECONDS);
                                activity.runOnUiThread(() -> done.accept(null));
                            } catch (Exception e) {
                                activity.runOnUiThread(() -> fail.accept(e));
                            }
                        },
                        "DriveDisconnect")
                .start();
    }

    public static boolean onActivityResult(
            Activity activity,
            int request,
            int result,
            Intent data,
            Consumer<String> onToken,
            Consumer<Throwable> onFailure) {
        if (request != REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null) {
            onFailure.accept(new IOException("Google Drive authorization cancelled"));
            return true;
        }
        AuthorizationResult authorization;
        try {
            authorization =
                    Identity.getAuthorizationClient(activity)
                            .getAuthorizationResultFromIntent(data);
        } catch (Exception e) {
            onFailure.accept(e);
            return true;
        }
        deliver(authorization, onToken, onFailure);
        return true;
    }

    private static void deliver(
            AuthorizationResult result, Consumer<String> onToken, Consumer<Throwable> onFailure) {
        String token;
        try {
            token = requireAuthorization(result.getAccessToken(), result.getGrantedScopes());
        } catch (IOException e) {
            onFailure.accept(e);
            return;
        }
        onToken.accept(token);
    }

    static String requireAuthorization(String token, List<String> granted) throws IOException {
        if (granted == null || !granted.contains(READ) || !granted.contains(STATE))
            throw new IOException(
                    "Drive needs read access and app data access; reconnect and grant both");
        DriveClient.requireToken(token);
        return token;
    }
}
