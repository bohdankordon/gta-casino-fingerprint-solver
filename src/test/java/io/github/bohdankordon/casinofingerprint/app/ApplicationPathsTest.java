package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The packaged application-root contract: an explicit property wins, the
 * historical user.dir fallback is preserved and the caller working directory
 * can never override an explicit packaged root.
 */
class ApplicationPathsTest {
    private static final Path USER_DIR =
            Path.of("test-user-dir").toAbsolutePath().normalize();

    @Test
    void missingPropertyFallsBackToUserDir() {
        assertEquals(USER_DIR,
                ApplicationPaths.runtimeDataRoot(null, USER_DIR.toString()));
    }

    @Test
    void blankPropertyFallsBackToUserDir() {
        assertEquals(USER_DIR,
                ApplicationPaths.runtimeDataRoot("   ", USER_DIR.toString()));
    }

    @Test
    void absolutePropertyIsUsedVerbatim() {
        Path root = USER_DIR.resolve("installed-app-payload").normalize();
        assertEquals(root,
                ApplicationPaths.runtimeDataRoot(root.toString(), USER_DIR.toString()));
    }

    @Test
    void relativePropertyResolvesAgainstUserDir() {
        assertEquals(USER_DIR.resolve("payload").normalize(),
                ApplicationPaths.runtimeDataRoot("payload", USER_DIR.toString()));
    }

    @Test
    void callerWorkingDirectoryNeverOverridesExplicitRoot() {
        Path root = USER_DIR.resolve("installed-app-payload").normalize();
        Path fromFirst = ApplicationPaths.runtimeDataRoot(root.toString(),
                USER_DIR.resolve("cwd-one").toString());
        Path fromSecond = ApplicationPaths.runtimeDataRoot(root.toString(),
                USER_DIR.resolve("cwd-two").toString());
        assertEquals(root, fromFirst);
        assertEquals(root, fromSecond);
    }
}
