package org.comicreader.app;

import java.io.File;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TransferFileTest {
    @Test public void uploadSourceLivesUntilNativeCompletion() throws Exception {
        File file = File.createTempFile("comicreader-test-", ".crp");
        try {
            TransferFile lifetime = new TransferFile(file, true); lifetime.discard();
            assertTrue(file.exists()); lifetime.complete(false); assertFalse(file.exists());
        } finally { file.delete(); }
    }
    @Test public void successfulDownloadBelongsToCallerUntilDiscarded() throws Exception {
        File file = File.createTempFile("comicreader-test-", ".part");
        try {
            TransferFile lifetime = new TransferFile(file, false); lifetime.complete(true);
            assertTrue(file.exists()); lifetime.discard(); assertFalse(file.exists());
        } finally { file.delete(); }
    }
    @Test public void completionAndCancellationCannotLeakTheFile() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                File file = File.createTempFile("comicreader-test-", ".part");
                TransferFile lifetime = new TransferFile(file, false);
                try {
                    Future<?> cancel = pool.submit(lifetime::discard), finish = pool.submit(() -> lifetime.complete(true));
                    cancel.get(2, TimeUnit.SECONDS); finish.get(2, TimeUnit.SECONDS); assertFalse(file.exists());
                } finally { file.delete(); }
            }
        } finally { pool.shutdownNow(); }
    }
}
