package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegionType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Geometry tests for the evaluation-only 1920x1080 hypothesis: edges are scaled and only then
 * turned into width and height, every derived region stays inside the native frame, and the result
 * is deterministic and passes the production layout contract.
 */
class EvaluationLayoutScalerTest {

    private static GameplayLayout productionLayout() throws IOException {
        return GameplayLayout.representative(
                RecordingTestSupport.PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
    }

    @Test
    void scaleFactorIsThreeQuartersForThe1080pRecording() {
        assertEquals(0.75, EvaluationLayoutScaler.uniformScaleFactor(2560, 1440, 1920, 1080), 1e-12);
    }

    @Test
    void sizesWithoutOneAspectRatioAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EvaluationLayoutScaler.uniformScaleFactor(2560, 1440, 1920, 1200));
    }

    @Test
    void candidateEdgesAreScaledBeforeWidthIsDerived() {
        GameplayRegion candidate =
                new GameplayRegion(GameplayRegionType.CANDIDATE, 0, 636, 364, 152, 152);

        GameplayRegion scaled = EvaluationLayoutScaler.scaleRegion(candidate, 0.75, 1920, 1080);

        assertEquals(477, scaled.x());
        assertEquals(273, scaled.y());
        assertEquals(114, scaled.width());
        assertEquals(114, scaled.height());
    }

    @Test
    void oppositeEdgesRoundConsistentlyOnAHalfPixel() {
        GameplayRegion target = new GameplayRegion(GameplayRegionType.TARGET, null, 1300, 196, 470, 695);

        GameplayRegion scaled = EvaluationLayoutScaler.scaleRegion(target, 0.75, 1920, 1080);

        assertEquals(975, scaled.x());
        assertEquals(Math.round(1770 * 0.75), scaled.x() + scaled.width());
        assertEquals(147, scaled.y());
        assertEquals(Math.round(891 * 0.75), scaled.y() + scaled.height());
    }

    @Test
    void everyDerivedRegionStaysInside1920x1080() throws IOException {
        List<GameplayRegion> scaled = EvaluationLayoutScaler.scaleRegions(
                productionLayout().regions(), 0.75, 1920, 1080);

        assertEquals(productionLayout().regions().size(), scaled.size());
        for (GameplayRegion region : scaled) {
            assertTrue(region.x() + region.width() <= 1920, region.toString());
            assertTrue(region.y() + region.height() <= 1080, region.toString());
            assertTrue(region.width() > 0 && region.height() > 0, region.toString());
        }
    }

    @Test
    void scalingIsDeterministic() throws IOException {
        List<GameplayRegion> first = EvaluationLayoutScaler.scaleRegions(
                productionLayout().regions(), 0.75, 1920, 1080);
        List<GameplayRegion> second = EvaluationLayoutScaler.scaleRegions(
                productionLayout().regions(), 0.75, 1920, 1080);

        assertEquals(first, second);
    }

    @Test
    void derivedManifestPassesTheProductionLayoutContract(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("derived.csv");

        GameplayLayout scaled = EvaluationLayoutScaler.writeAndRead(
                csv, productionLayout(), 1920, 1080);

        assertEquals(1920, scaled.sourceWidth());
        assertEquals(1080, scaled.sourceHeight());
        assertEquals(1, scaled.regions().stream()
                .filter(region -> region.regionType() == GameplayRegionType.TARGET).count());
        assertEquals(8, scaled.candidatesRowMajor().size());
        assertEquals(GameplayRegionType.TARGET, scaled.target().regionType());
    }

    @Test
    void regionThatWouldLeaveTheTargetFrameIsRejected() {
        GameplayRegion outside =
                new GameplayRegion(GameplayRegionType.CANDIDATE, 1, 2540, 1430, 100, 100);

        assertThrows(IllegalArgumentException.class,
                () -> EvaluationLayoutScaler.scaleRegion(outside, 0.75, 1920, 1080));
    }

    @Test
    void derivedManifestPathIsBuildOutputOnly() {
        assertTrue(EvaluationLayoutScaler.DERIVED_LAYOUT_REL.startsWith("target/"));
        assertTrue(EvaluationLayoutScaler.derivedManifestPath(RecordingTestSupport.PROJECT_ROOT)
                .endsWith(Path.of("stage6-derived-layout-1920x1080.csv")));
    }
}
