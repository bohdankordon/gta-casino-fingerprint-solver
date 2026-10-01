package io.github.bohdankordon.casinofingerprint.app;

import java.nio.file.Path;

/**
 * Narrow runtime-data-root abstraction for the packaged application.
 *
 * <p>Stage 9A assumed the working directory is the application-image root
 * (run-app-image.cmd changes into its own directory, so user.dir resolves the
 * packaged dataset and fixtures trees). A Start Menu launcher cannot rely on the
 * caller working directory, so the Stage 9B installer passes the application payload
 * root explicitly through a project-specific system property:
 *
 * <pre>
 * -Dgta.casino.solver.appRoot=APPDIR/app
 * </pre>
 *
 * <p>(APPDIR is the documented jpackage placeholder for the installed application
 * payload directory; see docs/windows-installer.md.)
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>a missing or blank property preserves the historical behavior, which is
 *       Path.of(System.getProperty("user.dir")) (development fallback);</li>
 *   <li>an absolute property value is used as-is (normalized);</li>
 *   <li>a relative property value is resolved against user.dir and normalized, so a
 *       misconfigured launcher degrades to a predictable location instead of an
 *       arbitrary relative path.</li>
 * </ul>
 *
 * <p>Only entry points that resolve packaged runtime data use this helper.
 * Recognition, navigation, input and lifecycle code is untouched.
 */
public final class ApplicationPaths {
    /** Project-specific system property carrying the packaged payload root. */
    public static final String APP_ROOT_PROPERTY = "gta.casino.solver.appRoot";

    private ApplicationPaths() {
    }

    /**
     * Resolves the runtime-data root for this process: the explicit packaged property
     * when present, otherwise the historical user.dir fallback.
     */
    public static Path runtimeDataRoot() {
        return runtimeDataRoot(System.getProperty(APP_ROOT_PROPERTY),
                System.getProperty("user.dir", "."));
    }

    /**
     * Testable resolution with injected values.
     *
     * @param configuredRoot value of APP_ROOT_PROPERTY, may be null or blank
     * @param userDir value of the user.dir system property, used as the fallback
     */
    static Path runtimeDataRoot(String configuredRoot, String userDir) {
        String base = (userDir == null || userDir.isBlank()) ? "." : userDir;
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return Path.of(base).toAbsolutePath().normalize();
        }
        Path configured = Path.of(configuredRoot.trim());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return Path.of(base).resolve(configured).toAbsolutePath().normalize();
    }
}
