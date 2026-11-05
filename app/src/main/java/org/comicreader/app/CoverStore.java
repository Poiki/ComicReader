package org.comicreader.app;

import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.util.LruCache;

import java.io.*;
import java.util.*;

/** Application-owned cover data shared by visible cells and background prefetch. */
final class CoverStore {
    static final class DecodeException extends IOException {
        DecodeException(Exception cause) {
            super("Cannot decode cover", cause);
        }
    }

    interface RemoteLoader {
        Bitmap load(String account, MegaClient.Node node) throws Exception;
    }

    private static CoverStore instance;

    static synchronized CoverStore get(Context context) {
        if (instance == null) instance = new CoverStore(context.getApplicationContext(), null);
        return instance;
    }

    private final Context context;
    private final RemoteLoader remoteLoader;
    final File directory;
    private final LruCache<String, Bitmap> memory =
            new LruCache<>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String id, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };

    CoverStore(Context context, RemoteLoader loader) {
        this.context = context.getApplicationContext();
        remoteLoader = loader;
        directory = new File(context.getCacheDir(), "covers-v1");
    }

    static String key(String account, MegaClient.Node node) {
        if (!account.matches("[A-Za-z0-9_-]{1,64}")
                || node.directory()
                || !ComicFiles.supported(node.name())
                || node.size() < 0
                || node.size() > ComicFiles.MAX_COMIC)
            throw new IllegalArgumentException("Invalid remote cover identity");
        return "mega_" + account + "_" + node.handle() + "_" + node.size();
    }

    Bitmap cached(String key) {
        return memory.get(key);
    }

    boolean contains(String key) {
        return new File(directory, key + ".jpg").isFile();
    }

    void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create cover cache");
    }

    boolean prefetchRoom() throws IOException {
        if (!directory.exists()) return true;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) throw new IOException("Cannot list cover cache");
        long bytes = 0;
        for (File file : files) bytes += file.length();
        return files.length < 1500 && bytes < 24L * 1024 * 1024;
    }

    synchronized Bitmap load(String key, Library.Book book, String account, MegaClient.Node node)
            throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Bitmap bitmap = memory.get(key);
        if (bitmap != null) {
            if (!contains(key)) save(new File(directory, key + ".jpg"), bitmap);
            return bitmap;
        }
        File cached = new File(directory, key + ".jpg");
        if (cached.isFile()) {
            try {
                bitmap = readImage(cached);
            } catch (IOException error) {
                android.util.Log.w("ComicReader", "Regenerating a damaged cached cover", error);
                if (!cached.delete()) throw new IOException("Cannot remove damaged cover", error);
            }
        }
        if (bitmap == null) {
            if (book != null) {
                try (PageSource source =
                        new PageSource(Library.get(context).file(book), book.format())) {
                    bitmap = source.render(0, 192, 288);
                }
            } else bitmap = fetch(account, node);
            if (bitmap == null) return null;
            float scale =
                    Math.min(1f, Math.min(192f / bitmap.getWidth(), 288f / bitmap.getHeight()));
            if (scale < 1f) {
                Bitmap resized =
                        Bitmap.createScaledBitmap(
                                bitmap,
                                Math.max(1, (int) (bitmap.getWidth() * scale)),
                                Math.max(1, (int) (bitmap.getHeight() * scale)),
                                true);
                bitmap.recycle();
                bitmap = resized;
            }
            if (Thread.currentThread().isInterrupted()) {
                bitmap.recycle();
                throw new InterruptedException();
            }
            save(cached, bitmap);
        }
        if (!cached.setLastModified(System.currentTimeMillis()))
            android.util.Log.w("ComicReader", "Could not update cover cache age");
        memory.put(key, bitmap);
        return bitmap;
    }

    static boolean batteryAllowed(Context context) {
        if (context.getSystemService(PowerManager.class).isPowerSaveMode()) return false;
        Intent battery =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null || !battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false);
    }

    static boolean remoteAllowed(Context context) {
        String mode = context.getSharedPreferences("appearance", 0).getString("covers", "wifi");
        if (mode.equals("off") || !batteryAllowed(context)) return false;
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        return Cloud.networkAllowed(
                network.getNetworkCapabilities(network.getActiveNetwork()), mode.equals("any"));
    }

    private Bitmap fetch(String account, MegaClient.Node node) throws Exception {
        if (remoteLoader != null) return remoteLoader.load(account, node);
        MegaClient mega = MegaClient.instance(context);
        if (!mega.loggedIn() || !remoteAllowed(context) || !account.equals(mega.account()))
            return null;
        File temporary = mega.thumbnail(node);
        boolean image = temporary != null;
        if (!image) temporary = mega.download(node);
        try {
            if (image) return readImage(temporary);
            String format =
                    switch (ComicFiles.extension(node.name())) {
                        case "pdf" -> "pdf";
                        case "cbr", "rar" -> "cbr";
                        default -> "cbz";
                    };
            try (PageSource source = new PageSource(temporary, format)) {
                return source.render(0, 192, 288);
            }
        } catch (IOException | RuntimeException error) {
            throw new DecodeException(error);
        } finally {
            if (temporary.exists() && !temporary.delete())
                android.util.Log.w("ComicReader", "Could not remove a temporary cover download");
        }
    }

    private static Bitmap readImage(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (file.length() > 5 * 1024 * 1024 || options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("Invalid cover image");
        options.inJustDecodeBounds = false;
        options.inSampleSize = ComicFiles.sampleSize(options.outWidth, options.outHeight, 192, 288);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Cannot decode the cover image");
        return bitmap;
    }

    private void save(File destination, Bitmap bitmap) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create cover cache");
        File temporary = File.createTempFile("cover-", ".tmp", directory);
        try {
            try (OutputStream out = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                    throw new IOException("Cannot encode cover");
            }
            if (!temporary.renameTo(destination)) throw new IOException("Cannot save cover cache");
            trim(directory);
            ComicWidget.changed(context);
        } finally {
            if (temporary.exists() && !temporary.delete())
                android.util.Log.w("ComicReader", "Could not remove a temporary cover image");
        }
    }

    static void trim(File directory) throws IOException {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) throw new IOException("Cannot list cover cache");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        int kept = 0;
        for (File file : files) {
            if (bytes + file.length() <= 32L * 1024 * 1024 && kept < 2000) {
                bytes += file.length();
                kept++;
            } else if (!file.delete()) throw new IOException("Cannot evict an old cover");
        }
    }
}
