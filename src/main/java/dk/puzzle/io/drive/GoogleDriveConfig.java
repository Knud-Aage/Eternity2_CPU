package dk.puzzle.io.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.File;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Configuration utility for integrating Google Drive API services into the Eternity II solver.
 *
 * <p>This class manages the OAuth2 authentication flow, handles token persistence
 * in a local directory, and provides helper methods for interacting with Google Drive
 * storage, such as directory management for cloud-based checkpoints.</p>
 */
public class GoogleDriveConfig {
    private static final String APPLICATION_NAME = "Eternity II Solver";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    /** The key the one stored sign-in lives under in {@code tokens/}. */
    static final String USER_ID = "user";
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final Logger logger = LogManager.getLogger(GoogleDriveConfig.class);

    /**
     * Connect and read timeout for every Drive call. The client library's 20 s default is short
     * enough that a single slow Drive response ("Read timed out") once ended a whole run's uploads.
     */
    private static final int HTTP_TIMEOUT_MILLIS = 60_000;

    /**
     * Nothing to authorize with: either {@code /credentials.json} is not on the classpath, or nobody has signed in yet
     * (no usable token in {@code tokens/}, see {@link #main}). The Drive problem that no retry can fix, and so the
     * only one {@link DriveUploader} treats as "switch uploads off".
     */
    public static final class MissingCredentialsException extends FileNotFoundException {
        public MissingCredentialsException(String message) {
            super(message);
        }
    }

    /**
     * Returns an authorized Google Drive service built from the sign-in stored in {@code tokens/}.
     *
     * <p>Never opens a browser or a port: the solver runs unattended, and a sign-in prompt on the one upload thread
     * would block every later upload for good. Without a usable stored sign-in this throws
     * {@link MissingCredentialsException}, which switches uploads off for the run. The sign-in is done once, by hand,
     * with {@link #main}.</p>
     *
     * @return An authorized {@link Drive} service instance ready for API operations.
     * @throws MissingCredentialsException If {@code /credentials.json} is not on the classpath, or no usable sign-in is
     *                   stored in {@code tokens/}.
     * @throws Exception If the transport cannot be initialized or the stored sign-in cannot be read.
     */
    public static Drive getDriveService() throws Exception {
        InputStream in = GoogleDriveConfig.class.getResourceAsStream("/credentials.json");
        if (in == null) {
            throw new MissingCredentialsException(">>> [ERROR] Couldn't find /credentials.json. Does it lay in src/main/resources?");
        }
        return getDriveService(in, new java.io.File(TOKENS_DIRECTORY_PATH));
    }

    /**
     * As {@link #getDriveService()} with the client secrets and the token folder given, so that a test can use a temporary one.
     * Reads and closes {@code clientSecretsJson}.
     */
    static Drive getDriveService(InputStream clientSecretsJson, java.io.File tokensDir) throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

        GoogleAuthorizationCodeFlow flow = buildFlow(HTTP_TRANSPORT, clientSecretsJson, tokensDir);
        Credential credential = flow.loadCredential(USER_ID);
        if (!isUsable(credential)) {
            throw new MissingCredentialsException("No Google sign-in is stored in " + tokensDir.getAbsolutePath()
                    + ". Sign in once, by hand, from the repository root: java -cp \"target/classes:$(cat cp.txt)\" "
                    + GoogleDriveConfig.class.getName() + " (see the README)");
        }

        HttpRequestInitializer withTimeouts = request -> {
            credential.initialize(request);
            request.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
            request.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        };

        return new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, withTimeouts)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /** The OAuth flow over the client secrets and the token folder. Reads and closes {@code clientSecretsJson}. */
    static GoogleAuthorizationCodeFlow buildFlow(NetHttpTransport httpTransport, InputStream clientSecretsJson,
                                                 java.io.File tokensDir) throws IOException {
        GoogleClientSecrets clientSecrets;
        try (InputStreamReader reader = new InputStreamReader(clientSecretsJson, StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }
        return new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir))
                .setAccessType("offline")
                .build();
    }

    /** The test the client library's own browser flow applies before it reuses a stored sign-in. */
    static boolean isUsable(Credential credential) {
        return credential != null
                && (credential.getRefreshToken() != null
                || credential.getExpiresInSeconds() == null
                || credential.getExpiresInSeconds() > 60);
    }

    /**
     * The one-time sign-in. Run it by hand, from the repository root and on a machine with a browser:
     * {@code java -cp "target/classes:$(cat cp.txt)" dk.puzzle.io.drive.GoogleDriveConfig}
     * Google's consent page opens (a local receiver on port 8888 catches the answer) and the token is stored in {@code tokens/},
     * after which the solver uploads on its own. On a machine without a browser, sign in elsewhere and copy {@code tokens/} across.
     * The solver itself never does this: unattended, it would wait for a browser that never comes.
     */
    public static void main(String[] args) throws Exception {
        InputStream in = GoogleDriveConfig.class.getResourceAsStream("/credentials.json");
        if (in == null) {
            System.err.println("Couldn't find /credentials.json on the class path -- put it in src/main/resources and rebuild.");
            System.exit(1);
        }
        java.io.File tokensDir = new java.io.File(TOKENS_DIRECTORY_PATH);
        GoogleAuthorizationCodeFlow flow = buildFlow(GoogleNetHttpTransport.newTrustedTransport(), in, tokensDir);
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        new AuthorizationCodeInstalledApp(flow, receiver).authorize(USER_ID);
        System.out.println("Signed in. The token is stored in " + tokensDir.getAbsolutePath() + "; the solver uploads to Drive from now on.");
    }

    /**
     * Searches for a folder by name in the user's Google Drive and creates it if it is not found.
     *
     * <p>This is used to ensure that puzzle records and logs are organized within a dedicated 
     * sub-folder in the cloud rather than cluttering the root directory.</p>
     *
     * @param driveService The authorized Google Drive service instance to use for the request.
     * @param folderName The display name of the folder to locate or initialize.
     * @return The unique Google Drive file ID for the folder.
     * @throws Exception If the API request fails, or if there is a conflict in retrieving or 
     *                   creating the folder metadata.
     */
    public static String getOrCreateFolder(Drive driveService, String folderName) throws Exception {
        String query = "mimeType='application/vnd.google-apps.folder' and name='" + folderName + "' and trashed=false";
        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }

        File folderMetadata = new File();
        folderMetadata.setName(folderName);
        folderMetadata.setMimeType("application/vnd.google-apps.folder"); // Dette gør filen til en mappe

        File folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute();

        logger.info(">>> [GOOGLE DRIVE] Oprettede ny mappe i skyen: " + folderName);
        return folder.getId();
    }
}