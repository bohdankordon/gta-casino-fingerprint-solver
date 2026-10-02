package io.github.bohdankordon.casinofingerprint.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The two explicit live profiles: stable 1440p and experimental 1080p, nothing else. */
class LiveGameplayProfileTest {
    private static final Path PROJECT_ROOT =
            Path.of(System.getProperty("user.dir")).toAbsolutePath();

    @Test
    void stableProfileIs2560x1440() {
        LiveGameplayProfile profile = LiveGameplayProfile.STABLE_1440P;
        assertEquals(2560, profile.resolution().width());
        assertEquals(1440, profile.resolution().height());
        assertEquals(LiveGameplayProfile.Stability.STABLE, profile.stability());
        assertFalse(profile.isExperimental());
        assertEquals(GameplayFixture.LAYOUT_REL, profile.layoutManifestRel());
        assertSame(ControlThresholds.PRODUCTION_1440P, profile.thresholds());
    }

    @Test
    void experimentalProfileIsExactly1920x1080() {
        LiveGameplayProfile profile = LiveGameplayProfile.EXPERIMENTAL_1080P;
        assertEquals(1920, profile.resolution().width());
        assertEquals(1080, profile.resolution().height());
        assertEquals(LiveGameplayProfile.Stability.EXPERIMENTAL, profile.stability());
        assertTrue(profile.isExperimental());
        assertTrue(profile.symbolicName().contains("EXPERIMENTAL"), profile.symbolicName());
        assertEquals("fixtures/gameplay/layout/experimental-1920x1080.csv",
                profile.layoutManifestRel());
        assertEquals(new ControlThresholds(79, 79, 315, 45, 20), profile.thresholds());
    }

    @Test
    void absentFlagSelectsOnlyTheStableProfile() {
        assertSame(LiveGameplayProfile.STABLE_1440P, LiveGameplayProfile.select(false));
    }

    @Test
    void experimentalFlagSelectsOnlyThe1080Profile() {
        assertSame(LiveGameplayProfile.EXPERIMENTAL_1080P, LiveGameplayProfile.select(true));
    }

    @Test
    void stableLayoutReadsSuccessfully() throws Exception {
        GameplayLayout layout = LiveGameplayProfile.STABLE_1440P.loadLayout(PROJECT_ROOT);
        assertEquals(2560, layout.sourceWidth());
        assertEquals(1440, layout.sourceHeight());
        assertEquals(8, layout.candidatesRowMajor().size());
    }

    @Test
    void experimentalLayoutReadsSuccessfully() throws Exception {
        GameplayLayout layout = LiveGameplayProfile.EXPERIMENTAL_1080P.loadLayout(PROJECT_ROOT);
        assertEquals(1920, layout.sourceWidth());
        assertEquals(1080, layout.sourceHeight());
        assertEquals(8, layout.candidatesRowMajor().size());
    }
}
