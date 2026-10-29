package org.comicreader.app;

import android.app.job.*;
import java.util.concurrent.*;

public final class SyncJob extends JobService {
    private ExecutorService executor;
    private Future<?> task;
    @Override public boolean onStartJob(JobParameters params) {
        if (!Cloud.configured(this) || !getSharedPreferences("cloud", 0).getBoolean("auto", false)) return false;
        ExecutorService jobExecutor = Executors.newSingleThreadExecutor(); executor = jobExecutor;
        task = jobExecutor.submit(() -> {
            boolean retry = false;
            try { Cloud.sync(this); }
            catch (Exception e) {
                retry = true;
                getSharedPreferences("cloud", 0).edit().putString("last_error", Ui.message(e)).apply();
            }
            finally { jobExecutor.shutdown(); }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (task != null) task.cancel(true);
        return Cloud.configured(this) && getSharedPreferences("cloud", 0).getBoolean("auto", false);
    }
    @Override public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
