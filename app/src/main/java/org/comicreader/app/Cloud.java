package org.comicreader.app;

import android.app.job.*;
import android.content.*;
import android.net.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;

final class Cloud {
    static boolean networkAllowed(NetworkCapabilities network, boolean allowMetered) {
        return network != null
                && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && (allowMetered
                        || network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    static boolean downloadAllowed(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        return networkAllowed(
                manager.getNetworkCapabilities(manager.getActiveNetwork()),
                context.getSharedPreferences("downloads", 0).getBoolean("metered", false));
    }

    static <T> T download(Context context, java.util.concurrent.Callable<T> work) throws Exception {
        Thread owner = Thread.currentThread();
        java.util.concurrent.atomic.AtomicBoolean blocked =
                new java.util.concurrent.atomic.AtomicBoolean();
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        class DownloadNetwork extends ConnectivityManager.NetworkCallback {
            private boolean active = true;

            private synchronized void check() {
                if (active && !downloadAllowed(context)) {
                    blocked.set(true);
                    owner.interrupt();
                }
            }

            synchronized void stop() {
                active = false;
            }

            @Override
            public void onCapabilitiesChanged(Network value, NetworkCapabilities capabilities) {
                check();
            }

            @Override
            public void onLost(Network value) {
                check();
            }
        }
        DownloadNetwork watch = new DownloadNetwork();
        network.registerDefaultNetworkCallback(watch);
        try {
            if (!downloadAllowed(context))
                throw new IOException(context.getString(R.string.download_network_required));
            return work.call();
        } catch (Exception error) {
            if (blocked.get())
                throw new IOException(context.getString(R.string.download_network_changed), error);
            throw error;
        } finally {
            watch.stop();
            network.unregisterNetworkCallback(watch);
            Thread.interrupted();
        }
    }

    static WebDav client(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        String endpoint = preferences.getString("endpoint", "");
        String authorization = Secrets.authorization(context), user = "";
        if (!authorization.isEmpty()) {
            if (!authorization.startsWith("Basic "))
                throw new IOException("Invalid saved WebDAV credentials");
            String credentials =
                    new String(
                            android.util.Base64.decode(
                                    authorization.substring(6), android.util.Base64.DEFAULT),
                            java.nio.charset.StandardCharsets.UTF_8);
            int colon = credentials.indexOf(':');
            if (colon < 0) throw new IOException("Invalid saved WebDAV credentials");
            user = credentials.substring(0, colon);
        }
        String previous = preferences.getString("webdav_id", "");
        String owner =
                user.isEmpty() && !authorization.isEmpty()
                        ? previous.startsWith("opaque-") ? previous : "opaque-" + UUID.randomUUID()
                        : Secrets.webDavIdentity(endpoint, user);
        if (!owner.equals(previous) && !preferences.edit().putString("webdav_id", owner).commit())
            throw new IOException("Could not save the WebDAV connection identity");
        return new WebDav(endpoint, authorization, owner);
    }

    static boolean configured(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        String provider = preferences.getString("provider", "");
        return BuildConfig.GOOGLE_SERVICES && provider.equals("drive")
                || provider.equals("mega")
                || provider.equals("webdav") && !preferences.getString("endpoint", "").isEmpty();
    }

    static synchronized int sync(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        String provider = preferences.getString("provider", "");
        CoverJob.pause(context);
        try {
            int count =
                    switch (provider) {
                        case "drive" -> new DriveClient(DriveAuth.silent(context)).sync(context);
                        case "mega" -> MegaClient.instance(context).sync(context);
                        case "webdav" -> syncWebDav(context);
                        default -> throw new IOException("Choose a cloud account before syncing");
                    };
            if (preferences.getString("provider", "").equals(provider))
                preferences
                        .edit()
                        .putLong("last_sync", System.currentTimeMillis())
                        .remove("last_error")
                        .apply();
            return count;
        } catch (Exception e) {
            if (preferences.getString("provider", "").equals(provider))
                preferences.edit().putString("last_error", Ui.message(e)).apply();
            throw e;
        } finally {
            if (provider.equals("mega")) {
                try {
                    MegaClient.instance(context).suspend();
                } catch (Exception e) {
                    android.util.Log.w("ComicReader", "MEGA could not disconnect after syncing", e);
                }
            }
            CoverJob.resume(context);
        }
    }

    private static int syncWebDav(Context context) throws Exception {
        WebDav dav = client(context);
        URI folder = dav.child(dav.root(), ".comicreader-state-v1/");
        dav.mkdir(folder);
        Map<String, Progress> merged = new HashMap<>();
        int devices = 0;
        long transferred = 0;
        for (WebDav.Entry entry : dav.list(folder)) {
            if (entry.directory() || !entry.name().matches("[0-9a-f-]{36}\\.crp")) continue;
            if (++devices > 128)
                throw new IOException("More than 128 devices; archive older snapshots");
            byte[] bytes = dav.get(entry.uri(), Progress.MAX_BYTES);
            transferred += bytes.length;
            if (transferred > 64L * 1024 * 1024)
                throw new IOException("WebDAV snapshots exceed 64 MiB per sync");
            for (Progress state : Progress.decode(bytes)) {
                Progress old = merged.get(state.id);
                merged.put(state.id, old == null ? state : Progress.latest(old, state));
                if (merged.size() > Progress.MAX_BOOKS)
                    throw new IOException("Too many remote comics");
            }
        }
        Library library = Library.get(context);
        library.merge(merged.values());
        dav.put(dav.child(folder, library.device + ".crp"), Progress.encode(library.states()));
        return merged.size();
    }

    static void schedule(Context context) {
        if (!configured(context)
                || !context.getSharedPreferences("cloud", 0).getBoolean("auto", false)) return;
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        JobInfo.Builder job =
                new JobInfo.Builder(1, new ComponentName(context, SyncJob.class))
                        .setRequiredNetworkType(
                                preferences.getBoolean("wifi", true)
                                        ? JobInfo.NETWORK_TYPE_UNMETERED
                                        : JobInfo.NETWORK_TYPE_ANY)
                        .setRequiresBatteryNotLow(true)
                        .setMinimumLatency(30_000)
                        .setBackoffCriteria(15 * 60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        int result = context.getSystemService(JobScheduler.class).schedule(job.build());
        if (result != JobScheduler.RESULT_SUCCESS)
            preferences
                    .edit()
                    .putString("last_error", "Android could not schedule synchronization")
                    .apply();
    }
}
