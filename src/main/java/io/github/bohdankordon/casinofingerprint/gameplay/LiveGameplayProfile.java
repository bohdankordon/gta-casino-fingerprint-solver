package io.github.bohdankordon.casinofingerprint.gameplay;

import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The single production way to pick a live gameplay geometry.
 *
 * <p>Stage 8D.2A adds a deliberately EXPERIMENTAL native 1920x1080 profile so a second PC
 * can perform an independent real-GTA validation while the known-good 2560x1440 checkout
 * stays frozen. This is NOT general arbitrary-resolution support: exactly two profiles
 * exist, the stable 2560x1440 one and the experimental 1920x1080 one. No other resolution
 * can be selected, no frame is ever resized, and no thresholds are ever derived from
 * arbitrary monitor dimensions at runtime.
 *
 * <p>Production runtime reads the committed manifest of the selected profile. It never
 * depends on the evaluation package; tests may compare the committed 1080 manifest against
 * {@code EvaluationLayoutScaler}.
 */
public final class LiveGameplayProfile {
    /** Whether a profile is validated for live play or pending independent validation. */
    public enum Stability {
        /** Validated live profile. */
        STABLE,
        /** Offline real-recording evidence only; live-PC validation pending. */
        EXPERIMENTAL
    }

    /** Stable 2560x1440 production profile: the only validated live geometry. */
    public static final LiveGameplayProfile STABLE_1440P = new LiveGameplayProfile(
            "1440p",
            new Resolution(2560, 1440),
            GameplayFixture.LAYOUT_REL,
            ControlThresholds.PRODUCTION_1440P,
            Stability.STABLE);

    /**
     * EXPERIMENTAL native 1920x1080 profile, available only through the explicit
     * {@code --enable-experimental-1080p} opt-in. Offline real-recording evidence exists
     * (2255 positive frames with zero unexplained mismatches, zero false recognitions on
     * 3513 exhaustive negatives, four stable correct rounds, 24/24 control-state rows),
     * but independent live-PC validation is still pending.
     */
    public static final LiveGameplayProfile EXPERIMENTAL_1080P = new LiveGameplayProfile(
            "1920x1080-EXPERIMENTAL",
            new Resolution(1920, 1080),
            "fixtures/gameplay/layout/experimental-1920x1080.csv",
            ControlThresholds.EXPERIMENTAL_1080P,
            Stability.EXPERIMENTAL);

    private final String symbolicName;
    private final Resolution resolution;
    private final String layoutManifestRel;
    private final ControlThresholds thresholds;
    private final Stability stability;

    private LiveGameplayProfile(String symbolicName, Resolution resolution,
            String layoutManifestRel, ControlThresholds thresholds, Stability stability) {
        this.symbolicName = Objects.requireNonNull(symbolicName, "symbolicName");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.layoutManifestRel = Objects.requireNonNull(layoutManifestRel, "layoutManifestRel");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.stability = Objects.requireNonNull(stability, "stability");
    }

    /**
     * Selects the live profile. The 1080p geometry is never picked implicitly: without
     * the explicit experimental opt-in the stable 1440p profile is always returned, even
     * on a machine that happens to have a 1080p monitor attached.
     *
     * @param experimental1080p the parsed {@code --enable-experimental-1080p} flag
     */
    public static LiveGameplayProfile select(boolean experimental1080p) {
        return experimental1080p ? EXPERIMENTAL_1080P : STABLE_1440P;
    }

    /** Symbolic name; the 1080p name always carries the EXPERIMENTAL marker. */
    public String symbolicName() {
        return symbolicName;
    }

    /** Required exact physical capture resolution; captures are never resized. */
    public Resolution resolution() {
        return resolution;
    }

    /** Repo-relative committed layout manifest path (forward slashes). */
    public String layoutManifestRel() {
        return layoutManifestRel;
    }

    /** Calibrated control-state bounds of this geometry. */
    public ControlThresholds thresholds() {
        return thresholds;
    }

    /** Whether this profile is validated or pending independent live validation. */
    public Stability stability() {
        return stability;
    }

    /** Whether this profile is the experimental 1080p one. */
    public boolean isExperimental() {
        return stability == Stability.EXPERIMENTAL;
    }

    /**
     * Reads the committed manifest of this profile and validates it against this
     * profile's own source dimensions.
     */
    public GameplayLayout loadLayout(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return GameplayLayout.read(
                projectRoot.resolve(layoutManifestRel.replace((char) 47, File.separatorChar)),
                resolution.width(), resolution.height());
    }
}
