package dk.puzzle.io.drive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveUploaderTest {

    private static final long[] FAST_PAUSES = {1, 1, 1};
    private static final String LINK = "https://e2.bucas.name/#puzzle=KnudHansen&board_w=16&board_h=16&board_edges=abc";

    private final List<String> attemptedNames = new CopyOnWriteArrayList<>();
    private final List<String> uploadedNames = new CopyOnWriteArrayList<>();
    private final List<String> uploadedContents = new CopyOnWriteArrayList<>();

    @AfterEach
    void putTheRealTransportBack() {
        DriveUploader.restoreDefaults();
    }

    private long attemptsFor(String prefix) {
        return attemptedNames.stream().filter(n -> n.startsWith(prefix + "_")).count();
    }

    private void record(String name, String content) {
        uploadedNames.add(name);
        uploadedContents.add(content);
    }

    @Test
    void oneTimeoutIsRetriedAndDoesNotSwitchOffLaterUploads() {
        // The Sep 19 failure: a single "Read timed out" used to disable every later upload.
        AtomicInteger callsForFirst = new AtomicInteger();
        DriveUploader.installForTest((name, content) -> {
            attemptedNames.add(name);
            if (name.startsWith("Errors14_first_") && callsForFirst.getAndIncrement() == 0) {
                throw new SocketTimeoutException("Read timed out");
            }
            record(name, content);
            return "id";
        }, FAST_PAUSES);

        DriveUploader.uploadRecord("Errors14_first", 14, LINK, "GPU");
        DriveUploader.uploadRecord("Errors14_second", 14, LINK, "GPU");
        assertTrue(DriveUploader.flush(10_000));

        assertEquals(2, attemptsFor("Errors14_first"), "the timed-out record is retried once, then succeeds");
        assertEquals(1, attemptsFor("Errors14_second"), "a later board is still uploaded");
        assertEquals(2, uploadedNames.size());
    }

    @Test
    void recordThatFailsEveryAttemptIsDroppedButLaterRecordsStillGo() {
        DriveUploader.installForTest((name, content) -> {
            attemptedNames.add(name);
            if (name.startsWith("Errors15_doomed_")) throw new SocketTimeoutException("Read timed out");
            record(name, content);
            return "id";
        }, FAST_PAUSES);

        DriveUploader.uploadRecord("Errors15_doomed", 15, LINK, "GPU");
        DriveUploader.uploadRecord("Errors13_fine", 13, LINK, "GPU");
        assertTrue(DriveUploader.flush(10_000));

        assertEquals(DriveUploader.MAX_ATTEMPTS, attemptsFor("Errors15_doomed"));
        assertEquals(1, attemptsFor("Errors13_fine"));
        assertEquals(1, uploadedNames.size());
        assertTrue(uploadedNames.get(0).startsWith("Errors13_fine_"));
    }

    @Test
    void missingCredentialsSwitchUploadsOffAfterOneAttempt() {
        DriveUploader.installForTest((name, content) -> {
            attemptedNames.add(name);
            throw new GoogleDriveConfig.MissingCredentialsException("no credentials.json");
        }, FAST_PAUSES);

        DriveUploader.uploadRecord("Errors14_a", 14, LINK, "GPU");
        assertTrue(DriveUploader.flush(10_000));
        DriveUploader.uploadRecord("Errors14_b", 14, LINK, "GPU");
        assertTrue(DriveUploader.flush(10_000));

        assertEquals(1, attemptedNames.size(), "no retry, and nothing after the switch-off");
    }

    @Test
    void recordCarriesConflictsSourceFoundTimeAndLink() {
        DriveUploader.installForTest((name, content) -> {
            record(name, content);
            return "id";
        }, FAST_PAUSES);

        DriveUploader.uploadRecord("Errors13_Base252_132727_442", 13, LINK, "GPU",
                LocalDateTime.of(2026, 9, 19, 13, 27, 27));
        assertTrue(DriveUploader.flush(10_000));

        assertEquals(1, uploadedNames.size());
        assertTrue(uploadedNames.get(0).matches("Errors13_Base252_132727_442_\\d+_link\\.txt"), uploadedNames.get(0));
        String content = uploadedContents.get(0);
        assertTrue(content.contains("Edge Conflicts: 13"), content);
        assertTrue(content.contains("Source: GPU"), content);
        assertTrue(content.contains("Time: 2026-09-19 13:27:27"), content);
        assertTrue(content.contains(LINK), content);
    }

    @Test
    void flushWaitsForEverythingQueuedSoFar() {
        DriveUploader.installForTest((name, content) -> {
            Thread.sleep(30);
            record(name, content);
            return "id";
        }, FAST_PAUSES);

        for (int i = 0; i < 5; i++) {
            DriveUploader.uploadRecord("Errors14_r" + i, 14, LINK, "GPU");
        }
        assertTrue(DriveUploader.flush(10_000));

        assertEquals(5, uploadedNames.size());
    }
}
