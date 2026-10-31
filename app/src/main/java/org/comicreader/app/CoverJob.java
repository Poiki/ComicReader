package org.comicreader.app;

import android.app.job.*;
import android.content.*;
import android.os.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToIntFunction;

/** One launch-triggered batch; Android retains the work when the library screen closes. */
public final class CoverJob extends JobService {
    static final int ID = 2;
    private static int foreground;
    private static Run active;
    private Run run;
    private final BroadcastReceiver battery =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!CoverStore.batteryAllowed(context)) {
                        synchronized (CoverJob.class) {
                            if (active != null) active.cancel();
                        }
                        context.getSystemService(JobScheduler.class).cancel(ID);
                        schedule(context, false);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_LOW);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(battery, filter);
    }

    static JobInfo plan(Context context, boolean urgent) {
        String mode = context.getSharedPreferences("appearance", 0).getString("covers", "wifi");
        PersistableBundle identity = new PersistableBundle();
        SharedPreferences library = context.getSharedPreferences("library", 0);
        identity.putString("account", library.getString("mega_account", ""));
        identity.putLong("root", library.getLong("mega_root", MegaClient.ROOT));
        identity.putString("mode", mode);
        JobInfo.Builder builder =
                new JobInfo.Builder(ID, new ComponentName(context, CoverJob.class))
                        .setExtras(identity)
                        .setRequiresStorageNotLow(true)
                        .setBackoffCriteria(5 * 60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (!mode.equals("off"))
            builder.setRequiredNetworkType(
                    mode.equals("any") ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED);
        // Expedited jobs reject battery constraints; the worker and receiver enforce that policy.
        if (urgent && Build.VERSION.SDK_INT >= 31) builder.setExpedited(true);
        else builder.setRequiresBatteryNotLow(true);
        return builder.build();
    }

    static boolean submit(JobInfo urgent, JobInfo regular, ToIntFunction<JobInfo> schedule) {
        if (schedule.applyAsInt(urgent) == JobScheduler.RESULT_SUCCESS) return true;
        if (Build.VERSION.SDK_INT < 31 || !urgent.isExpedited()) return false;
        android.util.Log.w(
                "ComicReader", "Expedited cover scheduling unavailable; using a regular job");
        return schedule.applyAsInt(regular) == JobScheduler.RESULT_SUCCESS;
    }

    static synchronized void schedule(Context context, boolean urgent) {
        if (foreground != 0) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo requested = plan(context, urgent && CoverStore.batteryAllowed(context));
        JobInfo pending = scheduler.getPendingJob(ID);
        if (pending != null
                && sameLibrary(pending, requested)
                && (active != null
                        || !urgent
                        || Build.VERSION.SDK_INT < 31
                        || pending.isExpedited())) return;
        if (!submit(requested, plan(context, false), scheduler::schedule))
            android.util.Log.e("ComicReader", "Android could not schedule cover preparation");
    }

    static boolean sameLibrary(JobInfo left, JobInfo right) {
        PersistableBundle a = left.getExtras(), b = right.getExtras();
        return Objects.equals(a.getString("account"), b.getString("account"))
                && a.getLong("root") == b.getLong("root")
                && Objects.equals(a.getString("mode"), b.getString("mode"));
    }

    static synchronized void pause(Context context) {
        foreground++;
        if (active != null) active.cancel();
        context.getSystemService(JobScheduler.class).cancel(ID);
    }

    static synchronized void resume(Context context) {
        if (foreground <= 0) throw new IllegalStateException("Unbalanced cover priority release");
        foreground--;
        if (foreground == 0) schedule(context.getApplicationContext(), false);
    }

    static synchronized boolean running() {
        return active != null && !active.stopped;
    }

    private static final class Run {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        volatile boolean stopped;
        Future<?> task;

        void cancel() {
            stopped = true;
            if (task != null) task.cancel(true);
            executor.shutdownNow();
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        synchronized (CoverJob.class) {
            if (foreground != 0) return false;
            Run current = new Run();
            active = run = current;
            current.task =
                    current.executor.submit(
                            () -> {
                                boolean retry = false;
                                try {
                                    android.os.Process.setThreadPriority(
                                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                                    retry = prepare(this, CoverStore.get(this));
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                    retry = !current.stopped;
                                } catch (Exception | OutOfMemoryError error) {
                                    retry = true;
                                    android.util.Log.w(
                                            "ComicReader", "Cover preparation interrupted", error);
                                } finally {
                                    boolean releaseSession;
                                    synchronized (CoverJob.class) {
                                        releaseSession =
                                                foreground == 0
                                                        && !current.stopped
                                                        && !Thread.currentThread().isInterrupted();
                                    }
                                    if (releaseSession && MegaClient.instance(this).loggedIn()) {
                                        try {
                                            MegaClient.instance(this).suspend();
                                        } catch (Exception error) {
                                            android.util.Log.w(
                                                    "ComicReader",
                                                    "MEGA could not disconnect after preparing covers",
                                                    error);
                                        }
                                    }
                                    current.executor.shutdown();
                                    synchronized (CoverJob.class) {
                                        if (active == current) active = null;
                                    }
                                }
                                if (!current.stopped) jobFinished(params, retry);
                            });
        }
        return true;
    }

    static boolean prepare(Context context, CoverStore store) throws Exception {
        if (!CoverStore.batteryAllowed(context)) return true;
        Library library = Library.get(context);
        for (Library.Book book : library.books()) {
            checkInterrupted();
            if (store.contains(book.id())) continue;
            if (!store.prefetchRoom()) return false;
            try {
                store.load(book.id(), book, null, null);
            } catch (IOException error) {
                checkInterrupted();
                android.util.Log.w(
                        "ComicReader", "Cannot prepare local cover: " + book.id(), error);
            }
        }
        if (!store.prefetchRoom()) return false;
        SharedPreferences preferences = context.getSharedPreferences("library", 0);
        String account = preferences.getString("mega_account", "");
        MegaClient mega = MegaClient.instance(context);
        if (account.isEmpty()
                || !mega.loggedIn()
                || context.getSharedPreferences("appearance", 0)
                        .getString("covers", "wifi")
                        .equals("off")) return false;
        if (!CoverStore.remoteAllowed(context)) return true;
        if (!account.equals(mega.account()))
            throw new IOException("The cover library belongs to another MEGA account");
        Deque<Long> folders = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        folders.add(preferences.getLong("mega_root", MegaClient.ROOT));
        int entries = 0;
        while (!folders.isEmpty()) {
            checkInterrupted();
            if (!CoverStore.remoteAllowed(context)) return true;
            long folder = folders.removeFirst();
            if (!visited.add(folder)) throw new IOException("Repeated folder in cover library");
            for (MegaClient.Node node : mega.list(folder)) {
                checkInterrupted();
                if (++entries > Progress.MAX_BOOKS)
                    throw new IOException("Cover library exceeds 50,000 entries");
                if (node.directory()) {
                    folders.addLast(node.handle());
                    continue;
                }
                if (!ComicFiles.supported(node.name())
                        || node.size() < 0
                        || node.size() > ComicFiles.MAX_COMIC) continue;
                String key = CoverStore.key(account, node);
                if (store.contains(key)) continue;
                if (!store.prefetchRoom()) return false;
                try {
                    store.load(key, null, account, node);
                } catch (CoverStore.DecodeException error) {
                    checkInterrupted();
                    android.util.Log.w(
                            "ComicReader", "Cannot prepare MEGA cover: " + node.handle(), error);
                }
            }
        }
        return false;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (run != null) run.cancel();
        synchronized (CoverJob.class) {
            return foreground == 0;
        }
    }

    @Override
    public void onDestroy() {
        if (run != null) run.cancel();
        unregisterReceiver(battery);
        super.onDestroy();
    }
}
