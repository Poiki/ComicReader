package org.comicreader.app;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.util.*;

/** Event-driven widgets use cached metadata and images, never network transfers. */
public class ComicWidget extends AppWidgetProvider {
    public static final class Continue extends ComicWidget {}

    public static final class Next extends ComicWidget {}

    public static final class Shelf extends ComicWidget {}

    private static final String REFRESH = "org.comicreader.app.WIDGET_REFRESH";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Boolean installed;
    private static Runnable pending;
    private static int foreground;
    private static final List<Class<? extends ComicWidget>> TYPES =
            List.of(Continue.class, Next.class, Shelf.class);

    static synchronized void changed(Context context) {
        Context app = context.getApplicationContext();
        if (installed == null)
            installed =
                    TYPES.stream()
                            .anyMatch(
                                    type ->
                                            AppWidgetManager.getInstance(app)
                                                            .getAppWidgetIds(
                                                                    new ComponentName(app, type))
                                                            .length
                                                    != 0);
        if (!installed) return;
        if (pending != null) MAIN.removeCallbacks(pending);
        if (foreground != 0) return;
        pending = () -> app.sendBroadcast(new Intent(app, Continue.class).setAction(REFRESH));
        MAIN.postDelayed(pending, 1500);
    }

    static synchronized void foreground(Context context, boolean visible) {
        foreground += visible ? 1 : -1;
        if (foreground < 0) throw new IllegalStateException("Unbalanced widget lifecycle");
        if (visible && pending != null) MAIN.removeCallbacks(pending);
        if (foreground == 0) changed(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (REFRESH.equals(intent.getAction())
                || Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
            updateAsync(context);
        else super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        installed = true;
        updateAsync(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context, AppWidgetManager manager, int id, Bundle options) {
        // Modern hosts select from the published layouts without rebuilding their data.
        if (Build.VERSION.SDK_INT < 31) updateAsync(context);
    }

    @Override
    public void onDisabled(Context context) {
        installed = null;
    }

    private void updateAsync(Context context) {
        BroadcastReceiver.PendingResult result = goAsync();
        Library.IO.execute(
                () -> {
                    try {
                        updateAll(context);
                    } catch (Exception error) {
                        android.util.Log.e("ComicReader", "Widget update failed", error);
                    } finally {
                        if (result != null) result.finish();
                    }
                });
    }

    static void updateAll(Context context) {
        Context display = AppLanguage.attach(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Map<Class<? extends ComicWidget>, int[]> ids = new LinkedHashMap<>();
        for (Class<? extends ComicWidget> type : TYPES) {
            int[] values = manager.getAppWidgetIds(new ComponentName(context, type));
            if (values.length != 0) ids.put(type, values);
        }
        if (ids.isEmpty()) {
            installed = false;
            return;
        }
        installed = true;
        List<ReadingShelf.Item> all = ReadingShelf.get(context).items();
        String last = context.getSharedPreferences("reader", 0).getString("last_book", "");
        String location = context.getSharedPreferences("reader", 0).getString("last_location", "");
        for (int index = 0; index < all.size(); index++)
            if (all.get(index).identity().equals(last)
                    && all.get(index).location().key().equals(location)) {
                Collections.swap(all, 0, index);
                break;
            }
        Map<String, Bitmap> images = new HashMap<>();
        for (var group : ids.entrySet()) {
            String mode =
                    group.getKey() == Continue.class
                            ? "continue"
                            : group.getKey() == Next.class ? "next" : "shelf";
            List<ReadingShelf.Item> items = ReadingShelf.select(all, mode, last);
            for (int id : group.getValue()) {
                Bundle options = manager.getAppWidgetOptions(id);
                RemoteViews views;
                if (Build.VERSION.SDK_INT >= 31) {
                    Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
                    for (int[] size :
                            new int[][] {
                                {140, 140}, {280, 160}, {280, 320}, {420, 320}, {280, 480}
                            })
                        layouts.put(
                                new SizeF(size[0], size[1]),
                                render(display, mode, items, size[0], size[1], images));
                    views = new RemoteViews(layouts);
                } else
                    views =
                            render(
                                    display,
                                    mode,
                                    items,
                                    options.getInt(
                                            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280),
                                    options.getInt(
                                            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160),
                                    images);
                manager.updateAppWidget(id, views);
            }
        }
    }

    static RemoteViews render(
            Context context,
            String mode,
            List<ReadingShelf.Item> items,
            int width,
            int height,
            Map<String, Bitmap> images) {
        String pkg = context.getPackageName();
        RemoteViews root = new RemoteViews(pkg, R.layout.widget_shelf);
        int background =
                Ui.theme(context).equals("light")
                        ? R.drawable.widget_light
                        : Ui.theme(context).equals("oled")
                                ? R.drawable.widget_oled
                                : R.drawable.widget_dark;
        root.setInt(R.id.widget_root, "setBackgroundResource", background);
        int title =
                mode.equals("continue")
                        ? R.string.widget_continue
                        : mode.equals("next") ? R.string.widget_next : R.string.widget_shelf;
        root.setTextViewText(R.id.widget_title, context.getString(title));
        root.setTextColor(R.id.widget_title, Ui.foreground(context));
        root.setInt(R.id.widget_refresh, "setColorFilter", Ui.accent(context));
        root.setContentDescription(R.id.widget_refresh, context.getString(R.string.widget_refresh));
        root.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                        context,
                        0,
                        new Intent(context, Continue.class).setAction(REFRESH),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        root.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(
                        context,
                        0,
                        new Intent(context, MainActivity.class)
                                .setAction("org.comicreader.app.LIBRARY")
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        root.removeAllViews(R.id.widget_content);
        int columns = width < 280 || height < 160 ? 1 : width >= 420 && height >= 320 ? 4 : 3;
        int rows = height >= 480 ? 3 : height >= 320 ? 2 : 1;
        int count = Math.min(items.size(), Math.min(9, columns * rows));
        root.setViewVisibility(R.id.widget_empty, count == 0 ? View.VISIBLE : View.GONE);
        root.setTextColor(R.id.widget_empty, Ui.secondary(context));
        root.setTextViewText(
                R.id.widget_empty,
                context.getString(
                        mode.equals("next")
                                ? R.string.widget_empty_next
                                : mode.equals("continue")
                                        ? R.string.widget_empty_continue
                                        : R.string.widget_empty_shelf));
        root.setOnClickPendingIntent(
                R.id.widget_empty,
                PendingIntent.getActivity(
                        context,
                        0,
                        new Intent(context, MainActivity.class)
                                .setAction("org.comicreader.app.LIBRARY")
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        boolean compact = columns == 1;
        float font = context.getResources().getConfiguration().fontScale;
        for (int start = 0; start < count; start += columns) {
            RemoteViews row = new RemoteViews(pkg, R.layout.widget_row);
            for (int column = 0; column < columns; column++) {
                RemoteViews card =
                        new RemoteViews(
                                pkg,
                                compact
                                        ? R.layout.widget_compact
                                        : font > 1.3f
                                                ? R.layout.widget_card_short
                                                : R.layout.widget_card);
                int index = start + column;
                if (index >= count) {
                    card.setViewVisibility(R.id.widget_item, View.INVISIBLE);
                    row.addView(R.id.widget_row, card);
                    continue;
                }
                ReadingShelf.Item item = items.get(index);
                card.setTextViewText(R.id.widget_item_title, item.title());
                card.setTextColor(R.id.widget_item_title, Ui.foreground(context));
                card.setInt(R.id.widget_item_title, "setMaxLines", compact && font <= 1.3f ? 3 : 1);
                if (!compact && Build.VERSION.SDK_INT >= 31) {
                    float coverHeight =
                            Math.max(
                                    24,
                                    Math.min(
                                            120,
                                            (height - 68f) / rows
                                                    - 14 * font
                                                    - (height >= 320 ? 13 * font : 0)
                                                    - 20));
                    card.setViewLayoutHeight(
                            R.id.widget_cover,
                            coverHeight,
                            android.util.TypedValue.COMPLEX_UNIT_DIP);
                    card.setViewLayoutWidth(
                            R.id.widget_cover,
                            coverHeight * 2 / 3,
                            android.util.TypedValue.COMPLEX_UNIT_DIP);
                }
                Progress progress = item.progress();
                int percent =
                        progress == null
                                ? 0
                                : item.completed()
                                        ? 100
                                        : progress.page == 0
                                                ? 0
                                                : (int)
                                                        ((progress.page + 1L)
                                                                * 100
                                                                / progress.pages);
                String detail =
                        progress != null && progress.page > 0
                                ? context.getString(
                                        R.string.widget_progress,
                                        progress.page + 1,
                                        progress.pages,
                                        percent)
                                : context.getString(
                                        item.book() == null
                                                ? R.string.widget_remote
                                                : R.string.ui_unread);
                card.setTextViewText(R.id.widget_detail, detail);
                card.setTextColor(R.id.widget_detail, Ui.secondary(context));
                card.setViewVisibility(
                        R.id.widget_detail, compact || height >= 320 ? View.VISIBLE : View.GONE);
                card.setContentDescription(R.id.widget_item, item.title() + ". " + detail);
                Bitmap image = image(context, item, images);
                if (image == null) {
                    card.setImageViewResource(R.id.widget_cover, R.drawable.icon_book);
                    card.setInt(R.id.widget_cover, "setColorFilter", Ui.accent(context));
                } else {
                    card.setInt(R.id.widget_cover, "setColorFilter", Color.TRANSPARENT);
                    card.setImageViewBitmap(R.id.widget_cover, image);
                }
                Bitmap bar =
                        images.computeIfAbsent(
                                "progress_" + percent + "_" + Ui.accent(context),
                                ignored -> {
                                    Bitmap bitmap =
                                            Bitmap.createBitmap(64, 2, Bitmap.Config.RGB_565);
                                    Canvas canvas = new Canvas(bitmap);
                                    canvas.drawColor(Ui.surface(context));
                                    Paint paint = new Paint();
                                    paint.setColor(Ui.accent(context));
                                    canvas.drawRect(0, 0, 64 * percent / 100f, 2, paint);
                                    return bitmap;
                                });
                card.setImageViewBitmap(R.id.widget_progress, bar);
                Intent intent =
                        ReadingShelf.open(context, item)
                                .setData(android.net.Uri.parse(item.location().key()));
                card.setOnClickPendingIntent(
                        R.id.widget_item,
                        PendingIntent.getActivity(
                                context,
                                0,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                row.addView(R.id.widget_row, card);
            }
            root.addView(R.id.widget_content, row);
        }
        return root;
    }

    private static Bitmap image(
            Context context, ReadingShelf.Item item, Map<String, Bitmap> images) {
        String key =
                item.book() != null
                        ? item.book().id()
                        : item.progress() != null ? item.progress().id : null;
        if (key != null && images.containsKey(key)) return images.get(key);
        File directory = CoverStore.get(context).directory;
        File file = key == null ? null : new File(directory, key + ".jpg");
        if ((file == null || !file.isFile()) && item.location().provider().equals("mega")) {
            ReadingShelf.Location source = item.location();
            key =
                    CoverStore.key(
                            source.owner(),
                            new MegaClient.Node(
                                    Long.parseLong(source.entry()),
                                    source.name(),
                                    false,
                                    source.size()));
            file = new File(directory, key + ".jpg");
        }
        if (key == null) return null;
        if (images.containsKey(key)) return images.get(key);
        Bitmap result = null;
        if (file != null && file.isFile() && file.length() <= 1024 * 1024) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap original = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (original != null) {
                int width =
                        Math.min(
                                96,
                                Math.max(
                                        48,
                                        (int)
                                                (64
                                                        * context.getResources()
                                                                .getDisplayMetrics()
                                                                .density)));
                result = Bitmap.createBitmap(width, width * 3 / 2, Bitmap.Config.RGB_565);
                new Canvas(result)
                        .drawBitmap(
                                original,
                                null,
                                new Rect(0, 0, result.getWidth(), result.getHeight()),
                                new Paint(Paint.FILTER_BITMAP_FLAG));
                original.recycle();
            }
        }
        images.put(key, result);
        return result;
    }
}
