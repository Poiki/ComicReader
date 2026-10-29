package org.comicreader.app;

import android.app.job.*;
import android.content.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;

final class Cloud {
    static WebDav client(Context context) throws Exception {
        return new WebDav(context.getSharedPreferences("cloud", 0).getString("endpoint", ""), Secrets.authorization(context));
    }
    static boolean configured(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        String provider = preferences.getString("provider", "");
        return provider.equals("drive") || provider.equals("mega") || provider.equals("webdav") && !preferences.getString("endpoint", "").isEmpty();
    }
    static synchronized int sync(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        String provider = preferences.getString("provider", "");
        try {
            int count = switch (provider) {
                case "drive" -> new DriveClient(DriveAuth.silent(context)).sync(context);
                case "mega" -> MegaClient.instance(context).sync(context);
                case "webdav" -> syncWebDav(context);
                default -> throw new IOException("Elige una cuenta en Nube antes de sincronizar");
            };
            if (preferences.getString("provider", "").equals(provider))
                preferences.edit().putLong("last_sync", System.currentTimeMillis()).remove("last_error").apply();
            return count;
        } catch (Exception e) {
            if (preferences.getString("provider", "").equals(provider)) preferences.edit().putString("last_error", Ui.message(e)).apply();
            throw e;
        }
        finally {
            if (provider.equals("mega")) {
                try { MegaClient.instance(context).suspend(); }
                catch (Exception e) { android.util.Log.w("ComicReader", "MEGA no pudo desconectarse después de sincronizar", e); }
            }
        }
    }
    private static int syncWebDav(Context context) throws Exception {
        WebDav dav = client(context);
        URI folder = dav.child(dav.root(), ".comicreader-state-v1/");
        dav.mkdir(folder);
        Map<String, Progress> merged = new HashMap<>(); int devices = 0; long transferred = 0;
        for (WebDav.Entry entry : dav.list(folder)) {
            if (entry.directory() || !entry.name().matches("[0-9a-f-]{36}\\.crp")) continue;
            if (++devices > 128) throw new IOException("Más de 128 dispositivos; archiva las instantáneas antiguas");
            byte[] bytes = dav.get(entry.uri(), Progress.MAX_BYTES);
            transferred += bytes.length;
            if (transferred > 64L * 1024 * 1024) throw new IOException("Las instantáneas WebDAV superan 64 MiB por sincronización");
            for (Progress state : Progress.decode(bytes)) {
                Progress old = merged.get(state.id);
                merged.put(state.id, old == null ? state : Progress.latest(old, state));
                if (merged.size() > Progress.MAX_BOOKS) throw new IOException("Demasiados cómics remotos");
            }
        }
        Library library = Library.get(context);
        library.merge(merged.values());
        dav.put(dav.child(folder, library.device + ".crp"), Progress.encode(library.states()));
        return merged.size();
    }
    static void schedule(Context context) {
        if (!configured(context) || !context.getSharedPreferences("cloud", 0).getBoolean("auto", false)) return;
        SharedPreferences preferences = context.getSharedPreferences("cloud", 0);
        JobInfo.Builder job = new JobInfo.Builder(1, new ComponentName(context, SyncJob.class))
                .setRequiredNetworkType(preferences.getBoolean("wifi", true) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true).setMinimumLatency(30_000)
                .setBackoffCriteria(15 * 60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        int result = context.getSystemService(JobScheduler.class).schedule(job.build());
        if (result != JobScheduler.RESULT_SUCCESS)
            preferences.edit().putString("last_error", "Android no pudo programar la sincronización").apply();
    }
}
