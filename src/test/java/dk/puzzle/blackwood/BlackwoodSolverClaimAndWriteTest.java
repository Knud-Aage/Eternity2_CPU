package dk.puzzle.blackwood;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BlackwoodSolver.claimAndWrite decides whether a completed board is written: the first worker to meet a board writes it, later
 * ones skip it, and a board whose write failed is not left remembered as saved. It used to be: the board was marked before its
 * files were written, so after one failed write (disk full, folder not writable) every later find of that board was skipped as
 * a duplicate and the board was lost for the rest of the run.
 */
class BlackwoodSolverClaimAndWriteTest {

    @Test
    void theFirstClaimWritesTheBoardAndRemembersIt() throws IOException {
        Set<String> saved = ConcurrentHashMap.newKeySet();
        AtomicInteger writes = new AtomicInteger();

        assertTrue(BlackwoodSolver.claimAndWrite(saved, "board", writes::incrementAndGet));

        assertEquals(1, writes.get());
        assertTrue(saved.contains("board"));
    }

    @Test
    void aBoardThatWasSavedBeforeIsNotWrittenAgain() throws IOException {
        Set<String> saved = ConcurrentHashMap.newKeySet();
        AtomicInteger writes = new AtomicInteger();
        BlackwoodSolver.claimAndWrite(saved, "board", writes::incrementAndGet);

        assertFalse(BlackwoodSolver.claimAndWrite(saved, "board", writes::incrementAndGet));

        assertEquals(1, writes.get());
    }

    @Test
    void aFailedWriteGivesTheBoardBackSoItIsWrittenWhenFoundAgain() throws IOException {
        Set<String> saved = ConcurrentHashMap.newKeySet();

        IOException thrown = assertThrows(IOException.class, () -> BlackwoodSolver.claimAndWrite(saved, "board", () -> {
            throw new IOException("disk full");
        }));
        assertEquals("disk full", thrown.getMessage());
        assertFalse(saved.contains("board"), "a board that was never written must not be remembered as saved");

        AtomicInteger writes = new AtomicInteger();
        assertTrue(BlackwoodSolver.claimAndWrite(saved, "board", writes::incrementAndGet));
        assertEquals(1, writes.get());
        assertTrue(saved.contains("board"));
    }

    @Test
    void aWriteThatFailsWithAnUncheckedExceptionGivesTheBoardBackToo() {
        Set<String> saved = ConcurrentHashMap.newKeySet();

        assertThrows(IllegalStateException.class, () -> BlackwoodSolver.claimAndWrite(saved, "board", () -> {
            throw new IllegalStateException("bug in a writer");
        }));

        assertFalse(saved.contains("board"));
    }

    @Test
    void aFailedWriteOfOneBoardDoesNotTouchTheOthers() throws IOException {
        Set<String> saved = ConcurrentHashMap.newKeySet();
        BlackwoodSolver.claimAndWrite(saved, "first", () -> { });

        assertThrows(IOException.class, () -> BlackwoodSolver.claimAndWrite(saved, "second", () -> {
            throw new IOException("disk full");
        }));

        assertTrue(saved.contains("first"));
        assertFalse(saved.contains("second"));
    }

    @Test
    void ofManyWorkersMeetingTheSameBoardAtOnceExactlyOneWritesIt() throws Exception {
        Set<String> saved = ConcurrentHashMap.newKeySet();
        AtomicInteger writes = new AtomicInteger();
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return BlackwoodSolver.claimAndWrite(saved, "board", () -> {
                        writes.incrementAndGet();
                        LockSupport.parkNanos(20_000_000L); // long enough for the others to arrive while this write is still running
                    });
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> r : results) {
                if (r.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
            assertEquals(1, writes.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
