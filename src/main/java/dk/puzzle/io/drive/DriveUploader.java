package dk.puzzle.io.drive;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors every saved board to a Google Drive folder, as a one-line text record.
 *
 * <p><b>This is an optional convenience and is deliberately easy to remove.</b> It exists so a
 * long unattended run can be checked from a phone. Nothing in the solver depends on it, and it
 * can be taken out three ways, in increasing order of permanence:</p>
 *
 * <ol>
 *   <li><b>Turn it off:</b> set the environment variable {@code ETERNITY_DRIVE_UPLOAD=false}.
 *       No rebuild, no code change. This is also the default behaviour when the Drive
 *       credentials are simply absent -- see below.</li>
 *   <li><b>Comment it out:</b> delete the single {@code DriveUploader.uploadRecord(...)} call in
 *       {@code BlackwoodGpuRunner.evaluateAndMaybeSave} (and the matching one in
 *       {@code BlackwoodSolver} if that path is in use). Nothing else references this package.</li>
 *   <li><b>Delete it entirely:</b> remove this {@code dk.puzzle.io.drive} package, the call site
 *       above, and the three {@code com.google.*} dependencies from {@code pom.xml}. The project
 *       compiles and runs unchanged.</li>
 * </ol>
 *
 * <p>Credentials are never committed: {@code GoogleDriveConfig} reads {@code /credentials.json}
 * from the classpath and writes OAuth tokens to {@code tokens/}, both of which this repository's
 * {@code .gitignore} excludes. With no credentials present, the first upload attempt is logged
 * once at WARN and uploads switch themselves off for the rest of the process -- so a fresh clone
 * runs correctly out of the box without any Drive setup at all.</p>
 *
 * <p>Uploads run on one background thread, so a slow Drive call never holds up the search. Only
 * missing credentials switch uploads off; any other failure is retried a few times, then dropped
 * for that one record while later boards are tried afresh. (An earlier version switched
 * everything off after the first failure of any kind, so one read timeout silenced a whole run.)</p>
 */
public final class DriveUploader {

    private static final Logger logger = LogManager.getLogger(DriveUploader.class);

    /** Drive folder that records are written into; created on first use if absent. */
    private static final String DRIVE_FOLDER = "Blackwood";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Tries per record before it is given up on. */
    static final int MAX_ATTEMPTS = 4;

    /** Pause before retry n (1-based): about ninety seconds in all. */
    private static final long[] DEFAULT_RETRY_PAUSES_MILLIS = {3_000, 15_000, 60_000};

    /** How long a normal JVM exit waits for records still queued. */
    private static final long SHUTDOWN_FLUSH_MILLIS = 15_000;

    /** One write into the Drive folder; returns the new file's id. Swappable so tests need no network. */
    interface Sender {
        String send(String fileName, String content) throws Exception;
    }

    private static volatile boolean enabled =
            !"false".equalsIgnoreCase(System.getenv("ETERNITY_DRIVE_UPLOAD"));

    private static volatile boolean noCredentials = false;
    private static volatile Sender sender = DriveUploader::sendToDrive;
    private static volatile long[] retryPausesMillis = DEFAULT_RETRY_PAUSES_MILLIS;

    /** Records dropped in a row; written only by the worker thread. */
    private static volatile int consecutiveDropped = 0;

    /** Guarded by {@code DriveUploader.class}. */
    private static ExecutorService worker;

    private DriveUploader() {
    }

    /**
     * Queues a small text record (conflicts, source, timestamp, bucas link) for one saved board,
     * stamped with the current time. Returns immediately.
     *
     * <p>Best-effort by contract: this is called only after the local save has already succeeded,
     * and never throws. A Drive problem must not cost a result that is already safely on disk.</p>
     *
     * @param prefix        the saved board's filename prefix, e.g. {@code Errors12_Base250_024804_563}
     * @param conflicts     the completed board's edge-conflict count
     * @param completedLink bucas link to the completed board
     * @param source        which engine produced it, e.g. {@code "GPU"}
     */
    public static void uploadRecord(String prefix, int conflicts, String completedLink, String source) {
        uploadRecord(prefix, conflicts, completedLink, source, LocalDateTime.now());
    }

    /**
     * As {@link #uploadRecord(String, int, String, String)}, but stamps the record with when the
     * board was found rather than now -- for back-filling boards whose upload was missed.
     */
    public static void uploadRecord(String prefix, int conflicts, String completedLink, String source,
                                    LocalDateTime foundAt) {
        if (!enabled || noCredentials) return;
        try {
            // Same name shape as the temp-file names this used to produce: <prefix>_<random>_link.txt.
            String fileName = prefix + "_" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) + "_link.txt";
            String content = String.format("Edge Conflicts: %d%nSource: %s%nTime: %s%n%s%n",
                    conflicts, source, foundAt.format(TIME_FORMAT), completedLink);
            worker().execute(() -> deliver(prefix, conflicts, fileName, content));
        } catch (RuntimeException e) {
            logger.warn("Drive upload of {} was not queued ({}); the board is already saved locally", prefix, e.toString());
        }
    }

    /**
     * Blocks until every record queued so far is uploaded or given up on, or the timeout passes.
     * A short-lived tool must call this before returning: the upload thread is a daemon.
     *
     * @return {@code true} if the queue drained in time
     */
    public static boolean flush(long timeoutMillis) {
        ExecutorService w;
        synchronized (DriveUploader.class) {
            w = worker;
        }
        if (w == null) return true;
        try {
            w.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "drive-uploader");
                t.setDaemon(true);
                return t;
            });
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> flush(SHUTDOWN_FLUSH_MILLIS), "drive-uploader-flush"));
        }
        return worker;
    }

    private static void deliver(String prefix, int conflicts, String fileName, String content) {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String id = sender.send(fileName, content);
                logger.info("Drive: uploaded {} ({} conflicts) to '{}', id {}{}",
                        prefix, conflicts, DRIVE_FOLDER, id, attempt == 1 ? "" : " (attempt " + attempt + ")");
                if (consecutiveDropped > 0) {
                    logger.info("Drive: uploads are working again ({} record(s) had been dropped)", consecutiveDropped);
                    consecutiveDropped = 0;
                }
                return;
            } catch (GoogleDriveConfig.MissingCredentialsException e) {
                noCredentials = true;
                logger.warn("Drive uploads are off for this run ({}). This is harmless -- boards are still "
                        + "saved locally. Set ETERNITY_DRIVE_UPLOAD=false to skip this entirely, or see "
                        + "DriveUploader's javadoc to remove Drive support.", e.getMessage());
                return;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS && !pauseBeforeRetry(attempt)) {
                    return;
                }
            }
        }
        consecutiveDropped++;
        // Log the start of an outage, then only every tenth drop, so a long outage doesn't flood the log.
        if (consecutiveDropped == 1 || consecutiveDropped % 10 == 0) {
            logger.warn("Drive upload of {} failed after {} attempts ({}); the board is already saved locally. "
                            + "Uploads stay on and the next board will be tried afresh ({} dropped in a row).",
                    prefix, MAX_ATTEMPTS, lastFailure, consecutiveDropped);
        } else {
            logger.debug("Drive upload of {} failed after {} attempts ({})", prefix, MAX_ATTEMPTS, lastFailure);
        }
    }

    /** @return {@code false} if the thread was interrupted and the record should be abandoned */
    private static boolean pauseBeforeRetry(int failedAttempt) {
        long[] pauses = retryPausesMillis;
        long pause = pauses[Math.min(failedAttempt, pauses.length) - 1];
        try {
            Thread.sleep(pause);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String sendToDrive(String fileName, String content) throws Exception {
        Drive driveService = GoogleDriveConfig.getDriveService();
        String folderId = GoogleDriveConfig.getOrCreateFolder(driveService, DRIVE_FOLDER);

        com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
        metadata.setName(fileName);
        metadata.setParents(Collections.singletonList(folderId));

        return driveService.files()
                .create(metadata, new ByteArrayContent("text/plain", content.getBytes(StandardCharsets.UTF_8)))
                .setFields("id")
                .execute()
                .getId();
    }

    /** Test seam: installs a fake transport with short retry pauses. */
    static void installForTest(Sender fake, long[] pausesMillis) {
        flush(5_000);
        enabled = true;
        noCredentials = false;
        consecutiveDropped = 0;
        sender = fake;
        retryPausesMillis = pausesMillis;
    }

    /** Test seam: restores the real transport and default pauses. */
    static void restoreDefaults() {
        flush(5_000);
        enabled = !"false".equalsIgnoreCase(System.getenv("ETERNITY_DRIVE_UPLOAD"));
        noCredentials = false;
        consecutiveDropped = 0;
        sender = DriveUploader::sendToDrive;
        retryPausesMillis = DEFAULT_RETRY_PAUSES_MILLIS;
    }
}
