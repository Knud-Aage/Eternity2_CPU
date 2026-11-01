package dk.puzzle.io.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.services.drive.Drive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The solver uploads unattended, on one background thread, so GoogleDriveConfig must never wait for a browser: with no stored sign-in it
 * fails at once with MissingCredentialsException, which DriveUploader turns into "uploads off for this run". It used to start a local
 * receiver on port 8888 and wait for a sign-in for ever, which blocked that thread and with it every later upload. The sign-in is now a
 * separate step (GoogleDriveConfig.main). These tests use a made-up OAuth client and a temporary token folder: no network, no browser.
 */
class GoogleDriveConfigTest {

    /** A made-up OAuth client in the shape of Google's "installed app" credentials.json. Nothing in it is real. */
    private static final String FAKE_CLIENT = "{\"installed\":{\"client_id\":\"test.apps.googleusercontent.com\","
            + "\"project_id\":\"test\",\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
            + "\"token_uri\":\"https://oauth2.googleapis.com/token\",\"client_secret\":\"dummy\","
            + "\"redirect_uris\":[\"http://localhost\"]}}";

    private static InputStream clientSecrets() {
        return new ByteArrayInputStream(FAKE_CLIENT.getBytes(StandardCharsets.UTF_8));
    }

    /** Stores a sign-in in the token folder, the way the one-time sign-in does, and returns it. */
    private static Credential store(File tokensDir, TokenResponse token) throws Exception {
        GoogleAuthorizationCodeFlow flow =
                GoogleDriveConfig.buildFlow(GoogleNetHttpTransport.newTrustedTransport(), clientSecrets(), tokensDir);
        return flow.createAndStoreCredential(token, GoogleDriveConfig.USER_ID);
    }

    @Test
    void withoutAStoredSignInItFailsAtOnceInsteadOfWaitingForABrowser(@TempDir File tokens) {
        GoogleDriveConfig.MissingCredentialsException e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            return assertThrows(GoogleDriveConfig.MissingCredentialsException.class,
                    () -> GoogleDriveConfig.getDriveService(clientSecrets(), tokens));
        });

        assertTrue(e.getMessage().contains(tokens.getAbsolutePath()), "it must say where it looked");
        assertTrue(e.getMessage().contains(GoogleDriveConfig.class.getName()), "it must say how to sign in");
    }

    @Test
    void aStoredSignInIsUsedWithoutAnyPrompt(@TempDir File tokens) throws Exception {
        store(tokens, new TokenResponse().setAccessToken("access").setRefreshToken("refresh").setExpiresInSeconds(3600L));

        Drive drive = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            return GoogleDriveConfig.getDriveService(clientSecrets(), tokens);
        });

        assertNotNull(drive);
    }

    @Test
    void aStoredTokenThatCannotBeRenewedAndIsAboutToExpireCountsAsNoSignIn(@TempDir File tokens) throws Exception {
        store(tokens, new TokenResponse().setAccessToken("access").setExpiresInSeconds(10L));

        assertThrows(GoogleDriveConfig.MissingCredentialsException.class,
                () -> GoogleDriveConfig.getDriveService(clientSecrets(), tokens));
    }

    @Test
    void aSignInIsUsableUnderTheSameRuleAsTheClientLibrarysOwnBrowserFlow(@TempDir File tokens) throws Exception {
        assertFalse(GoogleDriveConfig.isUsable(null), "nothing stored");
        assertTrue(GoogleDriveConfig.isUsable(store(tokens,
                new TokenResponse().setAccessToken("a").setRefreshToken("r").setExpiresInSeconds(10L))), "a refresh token can always renew it");
        assertTrue(GoogleDriveConfig.isUsable(store(tokens,
                new TokenResponse().setAccessToken("a"))), "no expiry known");
        assertTrue(GoogleDriveConfig.isUsable(store(tokens,
                new TokenResponse().setAccessToken("a").setExpiresInSeconds(3600L))), "still valid for an hour");
        assertFalse(GoogleDriveConfig.isUsable(store(tokens,
                new TokenResponse().setAccessToken("a").setExpiresInSeconds(10L))), "expires within a minute and cannot be renewed");
    }
}
