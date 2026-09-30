package io.github.bohdankordon.casinofingerprint.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.EvaluationLayoutScaler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The committed experimental 1080p manifest is exactly the Stage 6 edge-scaled derivation of
 * the committed 1440p manifest: uniform factor 0.75, rectangle edges rounded and only then
 * turned back into width and height. No manual coordinate tuning is allowed.
 */
class Experimental1080ManifestTest {
    private static final Path PROJECT_ROOT =
            Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private static final String LAYOUT_1080_REL =
            "fixtures/gameplay/layout/experimental-1920x1080.csv";

    @Test
    void scaleFactorIsUniform075() {
        assertEquals(0.75, EvaluationLayoutScaler.uniformScaleFactor(2560, 1440, 1920, 1080));
    }

    @Test
    void committed1080RegionsEqualTheScalerDerivation() throws Exception {
        GameplayLayout source = GameplayLayout.representative(
                PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
        GameplayLayout committed = GameplayLayout.read(
                PROJECT_ROOT.resolve(LAYOUT_1080_REL.replace('/', java.io.File.separatorChar)),
                1920, 1080);
        List<GameplayRegion> derived = EvaluationLayoutScaler.scaleRegions(
                source.regions(), 0.75, 1920, 1080);
        assertEquals(derived.size(), committed.regions().size(), "Region count");
        for (int i = 0; i < derived.size(); i++) {
            GameplayRegion expected = derived.get(i);
            GameplayRegion actual = committed.regions().get(i);
            assertEquals(expected.regionType(), actual.regionType(), "Type at " + i);
            assertEquals(expected.candidateIndex(), actual.candidateIndex(), "Index at " + i);
            assertEquals(expected.x(), actual.x(), "x at " + i);
            assertEquals(expected.y(), actual.y(), "y at " + i);
            assertEquals(expected.width(), actual.width(), "width at " + i);
            assertEquals(expected.height(), actual.height(), "height at " + i);
        }
    }

    @Test
    void committed1080CsvMatchesTheScalerRendering() throws Exception {
        GameplayLayout source = GameplayLayout.representative(
                PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
        String rendered = EvaluationLayoutScaler.manifestCsv(
                EvaluationLayoutScaler.scaleRegions(source.regions(), 0.75, 1920, 1080));
        String committed = Files.readString(
                PROJECT_ROOT.resolve(LAYOUT_1080_REL.replace('/', java.io.File.separatorChar)),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(rendered, committed, "Manifest bytes modulo line endings");
    }

    @Test
    void candidateTilesAre114Pixels() throws Exception {
        GameplayLayout committed = GameplayLayout.read(
                PROJECT_ROOT.resolve(LAYOUT_1080_REL.replace('/', java.io.File.separatorChar)),
                1920, 1080);
        for (GameplayRegion candidate : committed.candidatesRowMajor()) {
            assertEquals(114, candidate.width(), "Candidate " + candidate.candidateIndex());
            assertEquals(114, candidate.height(), "Candidate " + candidate.candidateIndex());
        }
        assertTrue(committed.candidatesRowMajor().size() == 8, "Eight candidates");
    }
}
