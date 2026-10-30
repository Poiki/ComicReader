package org.comicreader.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.*;

public final class MegaClientTest {
    @Test
    public void nextTransferWaitsForDelayedNativeCompletion() throws Exception {
        CountDownLatch nativeCompletion = new CountDownLatch(1), waiting = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> next =
                    executor.submit(
                            () -> {
                                waiting.countDown();
                                MegaClient.awaitCompletion(nativeCompletion);
                                return null;
                            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertFalse(next.isDone());
            nativeCompletion.countDown();
            next.get(2, TimeUnit.SECONDS);
        } finally {
            nativeCompletion.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void interruptionDoesNotPretendNativeWorkHasFinished() throws Exception {
        CountDownLatch nativeCompletion = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            MegaClient.awaitCompletion(nativeCompletion);
            fail("An abandoned waiter must stop immediately");
        } catch (InterruptedException expected) {
            assertEquals(1, nativeCompletion.getCount());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void stalledNativeWorkKeepsAnExplicitDiagnostic() throws Exception {
        CountDownLatch stalled =
                new CountDownLatch(1) {
                    @Override
                    public boolean await(long timeout, TimeUnit unit) {
                        assertEquals(30, unit.toSeconds(timeout));
                        return false;
                    }
                };
        try {
            MegaClient.awaitCompletion(stalled);
            fail("A stalled operation must not be silently ignored");
        } catch (IOException expected) {
            assertEquals(
                    "MEGA took too long to finish the previous operation; try again",
                    expected.getMessage());
            assertEquals(1, stalled.getCount());
        }
    }
}
