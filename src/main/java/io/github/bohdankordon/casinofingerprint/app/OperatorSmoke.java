package io.github.bohdankordon.casinofingerprint.app;

import com.formdev.flatlaf.FlatDarkLaf;
import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.debug.OpenCvHealthCheck;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic input-free startup smoke for the Stage 9B operator application.
 *
 * <p>Runs the same safe startup the installed GUI performs (application root,
 * FlatLaf, OpenCV natives, packaged reference data, gameplay layout, monitor
 * enumeration) and then exits DISARMED without ever arming a session. No Swing
 * window is created, no mouse or keyboard automation exists here, and no code
 * path in this class can send gameplay input.
 *
 * <p>Exit codes mirror the live CLI: 0 when every check passes, 3 on any failure.
 */
public final class OperatorSmoke {
    /** Exact FlatLaf version the Stage 9B UI is built against. */
    public static final String FLATLAF_VERSION = "3.7.2";
    /** Canonical reference crops every packaged manifest must describe. */
    public static final int EXPECTED_CROPS = 20;

    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 3;

    private OperatorSmoke() {
    }

    /**
     * Runs every input-free check.
     *
     * @param out activity stream, never System.out directly so the GUI can tee it
     * @param err error stream, never System.err directly
     * @param appRoot packaged runtime-data root under test
     * @return EXIT_OK when every check passes, EXIT_FAILURE otherwise
     */
    public static int run(PrintStream out, PrintStream err, Path appRoot) {
        try {
            check(out, appRoot);
            return EXIT_OK;
        } catch (Exception failed) {
            err.println("OPERATOR SMOKE FAILURE: " + failed);
            return EXIT_FAILURE;
        }
    }

    private static void check(PrintStream out, Path appRoot) throws Exception {
        if (appRoot == null) {
            throw new IllegalStateException("The application root is missing");
        }
        out.println("OPERATOR SMOKE: application root: " + appRoot.toAbsolutePath());
        out.println("OPERATOR SMOKE: java.home: " + System.getProperty("java.home", ""));
        out.println("OPERATOR SMOKE: java.version: "
                + System.getProperty("java.version", ""));
        out.println("OPERATOR SMOKE: os: " + System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " "
                + System.getProperty("os.arch", ""));

        String flatLafVersion =
                FlatDarkLaf.class.getPackage().getImplementationVersion();
        if (!FLATLAF_VERSION.equals(flatLafVersion)) {
            throw new IllegalStateException("FlatLaf version mismatch: expected "
                    + FLATLAF_VERSION + ", found " + flatLafVersion);
        }
        if (!FlatDarkLaf.setup()) {
            throw new IllegalStateException("FlatDarkLaf.setup() refused to install");
        }
        out.println("OPERATOR SMOKE: FlatLaf version: " + flatLafVersion);

        OpenCvHealthCheck.verify();
        out.println("OPERATOR SMOKE: OpenCV health: OK");

        Path manifest = appRoot.resolve(ReferenceFingerprintLibrary.MANIFEST_REL);
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException(
                    "The packaged reference manifest is missing: " + manifest);
        }
        List<ReferenceCrop> crops = ReferenceLayout.read(manifest);
        if (crops.size() != EXPECTED_CROPS) {
            throw new IllegalStateException("The reference manifest must describe exactly "
                    + EXPECTED_CROPS + " crops, found " + crops.size());
        }
        out.println("OPERATOR SMOKE: reference manifest: " + crops.size() + " crops");
        try (ReferenceFingerprintLibrary library =
                ReferenceFingerprintLibrary.load(appRoot)) {
            out.println("OPERATOR SMOKE: reference library: " + crops.size()
                    + " crops normalized");
        }

        Path layoutCsv = appRoot.resolve(GameplayFixture.LAYOUT_REL);
        GameplayLayout layout = GameplayLayout.representative(layoutCsv);
        if (layout.sourceWidth() != GameplayFixture.EXPECTED_WIDTH
                || layout.sourceHeight() != GameplayFixture.EXPECTED_HEIGHT) {
            throw new IllegalStateException("The packaged gameplay layout is not 2560x1440: "
                    + layout.sourceWidth() + "x" + layout.sourceHeight());
        }
        out.println("OPERATOR SMOKE: gameplay layout: " + layout.sourceWidth() + "x"
                + layout.sourceHeight());

        List<MonitorInfo> monitors = AwtMonitorEnumerator.create().enumerate();
        out.println("OPERATOR SMOKE: monitors (" + monitors.size() + "):");
        for (MonitorInfo monitor : monitors) {
            out.println("OPERATOR SMOKE:   " + monitor.describe());
        }

        out.println("OPERATOR STATE: DISARMED");
        out.println("OPERATOR INPUT: none sent");
    }
}
