package org.comicreader.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.Build;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

final class Updater implements AutoCloseable {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean();
    private volatile Call call;
    private volatile boolean cancelled;
    private boolean foreground;
    private ReleaseInfo offered;
    private AlertDialog downloadDialog;
    Updater(Activity activity) { this.activity = activity; }
    private SharedPreferences preferences() { return activity.getSharedPreferences("updates", 0); }
    private static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    }
    void onResume() {
        foreground = true;
        try {
            String downloaded = preferences().getString("download_version", "");
            if (!downloaded.isEmpty() && ReleaseInfo.compareVersions(downloaded, installed(activity).versionName) <= 0) {
                File file = UpdateProvider.apk(activity);
                if (file.exists() && !file.delete()) android.util.Log.w("ComicReader", "No se pudo retirar el APK ya instalado");
                preferences().edit().remove("download_version").remove("download_sha256").remove("download_size").putBoolean("pending_install", false).apply();
            }
        } catch (Exception error) { preferences().edit().putString("error", Ui.message(error)).apply(); }
        if (preferences().getBoolean("pending_install", false) && activity.getPackageManager().canRequestPackageInstalls()) { installSaved(); return; }
        if (offered != null) { showOffer(offered); offered = null; }
        if (preferences().getBoolean("auto", true)) check(false);
    }
    void onPause() { foreground = false; }
    void showSettings() {
        try {
            LinearLayout form = Ui.column(activity);
            String latest = preferences().getString("available", ""), error = preferences().getString("error", "");
            form.addView(Ui.text(activity, "Versión " + installed(activity).versionName + " · Poiki/ComicReader\n"
                    + (latest.isEmpty() ? "Actualizaciones desde GitHub Releases." : "Publicada: " + latest)
                    + (error.isEmpty() ? "" : "\nÚltima comprobación fallida: " + error), 16));
            CheckBox automatic = new CheckBox(activity); automatic.setText("Buscar al abrir, como máximo una vez al día");
            automatic.setChecked(preferences().getBoolean("auto", true));
            automatic.setOnCheckedChangeListener((view, checked) -> preferences().edit().putBoolean("auto", checked).apply()); form.addView(automatic);
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Actualizaciones").setView(form)
                    .setPositiveButton("Buscar ahora", (view, which) -> check(true)).setNegativeButton("Cerrar", null);
            if (UpdateProvider.apk(activity).isFile()) dialog.setNeutralButton("Instalar descargada", (view, which) -> installSaved());
            else dialog.setNeutralButton("Ver releases", (view, which) -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseInfo.REPOSITORY + "/releases"))));
            dialog.show();
        } catch (Exception error) { Ui.error(activity, error); }
    }
    void check(boolean manual) {
        long now = System.currentTimeMillis(), last = preferences().getLong("attempt", 0);
        if (!manual) {
            if (now >= last && now - last < TimeUnit.DAYS.toMillis(1)) return;
            ConnectivityManager network = activity.getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities = network.getNetworkCapabilities(network.getActiveNetwork());
            if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
        }
        if (!working.compareAndSet(false, true)) { if (manual) Ui.toast(activity, "Ya hay una actualización en curso"); return; }
        preferences().edit().putLong("attempt", now).apply(); cancelled = false;
        if (manual) Ui.toast(activity, "Buscando actualizaciones…");
        worker.execute(() -> {
            try {
                ReleaseInfo release;
                try (Response response = open(ReleaseInfo.API, 20)) {
                    if (response.code() == 404) release = null;
                    else {
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("GitHub respondió HTTP " + response.code());
                        release = ReleaseInfo.parse(new String(ComicFiles.read(response.body().byteStream(), ReleaseInfo.MAX_JSON), StandardCharsets.UTF_8), installed(activity).versionName, Build.SUPPORTED_ABIS);
                    }
                }
                preferences().edit().remove("error").putString("available", release == null ? "" : release.version()).apply();
                ReleaseInfo result = release;
                activity.runOnUiThread(() -> {
                    if (!alive()) return;
                    if (result == null) { if (manual) Ui.toast(activity, "No hay una versión nueva publicada"); }
                    else if (foreground) showOffer(result); else offered = result;
                });
            } catch (Exception error) {
                preferences().edit().putString("error", Ui.message(error)).apply();
                if (manual) activity.runOnUiThread(() -> { if (alive()) Ui.error(activity, error); });
            } finally { working.set(false); }
        });
    }
    private boolean alive() { return !activity.isDestroyed() && !activity.isFinishing(); }
    private void showOffer(ReleaseInfo release) {
        new AlertDialog.Builder(activity).setTitle("ComicReader " + release.version())
                .setMessage("Hay una versión nueva en GitHub Releases (" + String.format(Locale.getDefault(), "%.1f MiB", release.size() / 1048576.0)
                        + "). Se conservarán biblioteca, cuentas y progreso. Android pedirá confirmar la instalación.")
                .setPositiveButton("Descargar e instalar", (dialog, which) -> download(release)).setNegativeButton("Ahora no", null).show();
    }
    private Response open(String url, int seconds) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            if (cancelled) throw new InterruptedIOException("Actualización cancelada");
            if (!ReleaseInfo.trustedUrl(url)) throw new IOException("GitHub redirigió a un origen no autorizado");
            Request request = new Request.Builder().url(url).header("User-Agent", "ComicReader Android")
                    .header("Accept", url.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "application/octet-stream").build();
            call = HTTP.newCall(request); call.timeout().timeout(seconds, TimeUnit.SECONDS);
            Response response = call.execute();
            if (!response.isRedirect()) return response;
            String location = response.header("Location"); HttpUrl next = location == null ? null : response.request().url().resolve(location); response.close();
            if (next == null) throw new IOException("Redirección GitHub inválida"); url = next.toString();
        }
        throw new IOException("Demasiadas redirecciones de GitHub");
    }
    private void download(ReleaseInfo release) {
        if (!working.compareAndSet(false, true)) { Ui.toast(activity, "Espera a la comprobación actual"); return; }
        cancelled = false;
        ProgressBar progress = new ProgressBar(activity); progress.setContentDescription("Descargando actualización");
        downloadDialog = new AlertDialog.Builder(activity).setTitle("Descargando " + release.version()).setView(progress)
                .setNegativeButton("Cancelar", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()).show();
        worker.execute(() -> {
            File staged = null;
            try {
                File ready = UpdateProvider.apk(activity), directory = ready.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No se puede crear la caché de actualización");
                staged = File.createTempFile("update-", ".part", directory);
                try (Response response = open(release.url(), 600)) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Descarga GitHub HTTP " + response.code());
                    try (FileOutputStream output = new FileOutputStream(staged)) {
                        long count = ComicFiles.copy(response.body().byteStream(), output, release.size()); output.getFD().sync();
                        if (count != release.size()) throw new IOException("Descarga APK incompleta");
                    }
                }
                verify(activity, staged, release.version(), release.sha256(), release.size());
                if (cancelled) throw new InterruptedIOException("Actualización cancelada");
                Files.move(staged.toPath(), ready.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (!preferences().edit().putString("download_version", release.version()).putString("download_sha256", release.sha256())
                        .putLong("download_size", release.size()).putBoolean("pending_install", true).commit()) throw new IOException("No se puede guardar la actualización");
                activity.runOnUiThread(() -> { if (alive()) { dismissDownload(); requestInstall(); } });
            } catch (Exception error) {
                activity.runOnUiThread(() -> { if (alive()) { dismissDownload(); if (!cancelled) Ui.error(activity, error); } });
            } finally {
                if (staged != null && staged.exists() && !staged.delete()) android.util.Log.w("ComicReader", "No se pudo eliminar un APK parcial");
                working.set(false);
            }
        });
    }
    private void cancel() { cancelled = true; Call active = call; if (active != null) active.cancel(); }
    private void dismissDownload() { if (downloadDialog != null) { downloadDialog.dismiss(); downloadDialog = null; } }
    private void installSaved() {
        if (!working.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                verify(activity, UpdateProvider.apk(activity), preferences().getString("download_version", ""),
                        preferences().getString("download_sha256", ""), preferences().getLong("download_size", 0));
                activity.runOnUiThread(() -> { if (alive()) requestInstall(); });
            } catch (Exception error) {
                preferences().edit().putBoolean("pending_install", false).apply();
                activity.runOnUiThread(() -> { if (alive()) Ui.error(activity, error); });
            } finally { working.set(false); }
        });
    }
    private void requestInstall() {
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                preferences().edit().putBoolean("pending_install", true).apply();
                Ui.toast(activity, "Permite instalar actualizaciones desde ComicReader y vuelve a la app");
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } else {
                preferences().edit().putBoolean("pending_install", false).apply();
                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(UpdateProvider.URI, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
        } catch (Exception error) { Ui.error(activity, error); }
    }
    static void verify(Context context, File file, String version, String sha256, long size) throws Exception {
        if (!file.isFile() || size < 1 || size > ReleaseInfo.MAX_APK || file.length() != size || !sha256.matches("[0-9a-f]{64}"))
            throw new IOException("APK incompleto o sin SHA-256 válido; vuelve a descargarlo");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024]; long total = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Verificación cancelada");
                total += count; if (total > size) throw new IOException("El tamaño del APK cambió durante la verificación");
                digest.update(buffer, 0, count);
            }
            if (total != size) throw new IOException("APK incompleto");
        }
        StringBuilder actual = new StringBuilder(); for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!sha256.contentEquals(actual)) throw new IOException("El SHA-256 del APK no coincide con la release");
        PackageInfo current = installed(context), candidate = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !current.packageName.equals(candidate.packageName) || candidate.getLongVersionCode() <= current.getLongVersionCode()
                || ReleaseInfo.compareVersions(candidate.versionName, version) != 0 || ReleaseInfo.compareVersions(candidate.versionName, current.versionName) <= 0)
            throw new IOException("El APK no es una versión posterior de ComicReader");
        if (current.signingInfo == null || candidate.signingInfo == null
                || !new HashSet<>(Arrays.asList(current.signingInfo.getApkContentsSigners())).equals(new HashSet<>(Arrays.asList(candidate.signingInfo.getApkContentsSigners()))))
            throw new IOException("La firma del APK no coincide con tu ComicReader instalado");
        if (candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("La actualización necesita otra versión de Android");
    }
    @Override public void close() { cancel(); dismissDownload(); worker.shutdownNow(); }
}
