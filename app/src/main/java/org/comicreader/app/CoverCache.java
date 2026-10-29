package org.comicreader.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Covers are decoded only when a library cell becomes visible. */
final class CoverCache implements AutoCloseable {
    static final class CoverView extends ImageView {
        volatile String bookId;
        CoverView(android.content.Context context) { super(context); }
    }
    private final Activity activity;
    private final LruCache<String, Bitmap> memory = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String id, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    CoverCache(Activity activity) { this.activity = activity; }
    void bind(CoverView view, Library.Book book) {
        view.bookId = book.id();
        Bitmap cached = memory.get(book.id());
        if (cached != null) { view.setImageBitmap(cached); return; }
        android.graphics.drawable.Drawable placeholder = activity.getDrawable(R.drawable.icon_book).mutate();
        placeholder.setTint(Ui.foreground(activity)); view.setImageDrawable(placeholder);
        worker.getQueue().removeIf(work -> work instanceof Load load && !load.wanted());
        worker.execute(new Load(view, book));
    }
    private final class Load implements Runnable {
        private final WeakReference<CoverView> target;
        private final Library.Book book;
        Load(CoverView view, Library.Book book) { target = new WeakReference<>(view); this.book = book; }
        boolean wanted() { CoverView view = target.get(); return view != null && book.id().equals(view.bookId); }
        public void run() {
            if (!wanted() || worker.isShutdown()) return;
            try {
                Bitmap bitmap = memory.get(book.id());
                if (bitmap == null) {
                    try (PageSource source = new PageSource(Library.get(activity).file(book), book.format())) {
                        bitmap = source.render(0, 192, 288);
                    }
                    float scale = Math.min(1f, Math.min(192f / bitmap.getWidth(), 288f / bitmap.getHeight()));
                    if (scale < 1f) {
                        Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (bitmap.getWidth() * scale)),
                                Math.max(1, (int) (bitmap.getHeight() * scale)), true);
                        bitmap.recycle(); bitmap = thumbnail;
                    }
                    if (worker.isShutdown()) return;
                    memory.put(book.id(), bitmap);
                }
                Bitmap loaded = bitmap;
                activity.runOnUiThread(() -> {
                    CoverView view = target.get();
                    if (view != null && book.id().equals(view.bookId) && !activity.isDestroyed()) view.setImageBitmap(loaded);
                });
            } catch (Exception | OutOfMemoryError error) {
                // A missing cover keeps the book accessible; opening it reports the file error.
                android.util.Log.w("ComicReader", "No se pudo cargar la portada: " + book.id(), error);
            }
        }
    }
    void cancelPending() { worker.getQueue().clear(); }
    @Override public void close() { worker.shutdownNow(); memory.evictAll(); }
}
