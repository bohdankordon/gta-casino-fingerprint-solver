# Structural matching (Stage 3)

Stage 3 answers two measurement questions:

1. which of the four known reference targets does the observed gameplay target look like, with a
   score for every candidate target;
2. how similar is each of the eight observed candidates to each of the four reference fragments of
   the identified target, as a complete 8x4 matrix.

It produces no answer to the puzzle. Candidate selection, ambiguity handling and confidence belong
to Stage 4.

## Data flow

```text
gameplay frame
  -> GameplayFrameExtractor            (Stage 2 ROIs, row-major 0..7)
  -> StructuralNormalizer              (256x384 target, 128x128 fragments)
  -> TargetMatcher  -> 4 scores + deterministic ranking + top-1/top-2 margin
  -> FragmentMatcher -> 8 x 4 similarity matrix for the top ranked target
```

Reference material flows through the same normalizer: `ReferenceFingerprintLibrary` reads the
Stage 1 manifest (`dataset/layout/reference-layout.csv`), decodes the 20 canonical assets and owns
their normalized Mat profiles until `close()`.

## Score contract

`SimilarityScore` is an immutable value in `[0.0, 1.0]`, where `1.0` means the two normalized
profiles are structurally identical at the best alignment and `0.0` means no structural
correlation. Scores are finite and deterministic for the same inputs. The score scale carries no
fixture-specific meaning and no candidate-correctness information.

## Selected algorithm

One signal, shared by both profiles: **translation-tolerant zero-mean normalized cross-correlation**
(`StructuralSimilarityScorer`).

For a reference profile R and an observation O of the same size, the scorer takes the centered
interior window of O, slides it inside a `+/- radius` window of R, evaluates OpenCV
`matchTemplate(..., TM_CCOEFF_NORMED)` and keeps the best correlation. The raw correlation is
clamped to `[0, 1]`, so anti-correlated or uncorrelated structure scores 0. A profile without
intensity variation (standard deviation below one intensity level) has no structure to correlate
and scores 0 instead of the spurious perfect score OpenCV reports for a flat window.

Zero-mean normalization removes constant brightness and contrast offsets, and
`StructuralNormalizer` has already removed the large brightness gap between unselected and
selected tiles, so what remains is ridge geometry. Radiometric invariance is what makes the same
scorer work for dim candidates, bright candidates and clean reference crops.

## Approaches investigated (measured, not assumed)

All numbers below were measured on the normalized Stage 1 references and the representative Stage 2
fixture with the ground truth in `fixtures/gameplay/annotations/representative-2560x1440.csv`.

Target profile (256x384, correct target FP_1):

| method | FP_1 | runner-up | top-1 minus top-2 |
| --- | --- | --- | --- |
| zero-mean normalized correlation (chosen) | 0.610 | 0.179 (FP_2) | 0.431 |
| Otsu-mask overlap (Dice) | 0.695 | 0.424 (FP_2) | 0.242 |
| Canny distance transform (chamfer, lower is better) | 1.749 | 1.796 (FP_3) | -0.047 inverse |
| Sobel-gradient correlation | 0.382 | 0.165 (FP_2) | 0.217 |

Fragment profile (128x128, FP_1 fragments, correct pair versus strongest incorrect candidate):

| method | correct pairs | strongest incorrect | separation |
| --- | --- | --- | --- |
| zero-mean normalized correlation (chosen) | 0.927 - 0.975 | 0.149 | 0.78 - 0.86 |
| Otsu-mask overlap (Dice) | 0.899 - 0.947 | 0.492 | 0.41 - 0.46 |
| Canny distance transform (chamfer, lower is better) | 0.29 - 0.48 px | 1.41 - 1.70 px | about 1 px |

Conclusions from the measurements:

- Correlation is the strongest and simplest signal on both profiles. Binarized overlap compresses
  everything into a narrow band (unrelated fingerprints still overlap about 0.4-0.5 once ridges are
  thickened by thresholding), which weakens separation.
- Chamfer matching failed on the target profile: FP_1 and FP_3 finished within 0.05 px of each
  other, because the distance transform tolerates the ridge spacing that actually distinguishes the
  prints. It worked on fragments but no better than correlation while adding edge-threshold and
  distance-transform parameters.
- Gradient correlation behaves like a weaker version of the chosen signal: same ranking, about half
  the margin.
- An ensemble was therefore not justified. One generic metric gives margins of 0.43 (target) and
  about 0.8 (fragments) with no fixture-specific constants.
- Sparse keypoint methods (ORB/SIFT) were considered and not pursued: the ridge fields are smooth
  and repetitive, which is poor keypoint material, and the dense structural metric already reaches
  wide, stable margins on every correct pair.
- No scale search is used. Sweeping the reference scale from 0.85 to 1.15 showed the correlation
  peak at exactly 1.00 for all four correct fragment pairs and collapsing to at most 0.32 at 15%
  scale error, so the normalized profiles are scale-consistent and a scale search would add cost
  without measured benefit.

## Translation tolerance

Both profiles use the same relative tolerance, about +/- 3% of profile width, expressed as the two
profile sizes differ:

| profile | search radius | measured effect (correct pair / strongest incorrect) |
| --- | --- | --- |
| fragment 128x128 | +/- 4 px | radius 2 fails under a 4 px shift (correct pair drops to 0.61-0.67); radius 5-6 raises incorrect pairs to 0.24-0.25 without helping correct ones; 4 px keeps correct pairs at 0.94-0.96 under shifts up to 4 px with incorrect pairs at most 0.24 |
| target 256x384 | +/- 8 px | radii 6, 8 and 10 all keep FP_1 first with a margin of roughly 0.42-0.43; 8 px is the same relative tolerance as the fragment profile |

Rationale: the two crop sources differ by a few pixels of ROI inset (the gameplay layout insets 4 px
inside tile border centers, the reference crops inset 3 px) plus measurement error, so a small
search window absorbs realistic misalignment. Larger windows were rejected because they let
unrelated patterns slide into a match, which is visible in the incorrect-pair scores above.

## Representative fixture results

Target scores (all four retained):

```text
FP_1 0.6102   FP_2 0.1788   FP_3 0.1720   FP_4 0.1572
ranking FP_1 > FP_2 > FP_3 > FP_4, top-1 minus top-2 margin 0.4315
```

Fragment matrix for FP_1 (candidate x reference fragment):

```text
                    FRAGMENT_1  FRAGMENT_2  FRAGMENT_3  FRAGMENT_4
CANDIDATE_0             0.0823      0.9546      0.1074      0.0973
CANDIDATE_1             0.0816      0.0656      0.1091      0.1011
CANDIDATE_2             0.0657      0.0634      0.0918      0.1486
CANDIDATE_3             0.1106      0.0885      0.9677      0.1478
CANDIDATE_4             0.0673      0.0806      0.0739      0.0607
CANDIDATE_5             0.0684      0.1031      0.0644      0.0286
CANDIDATE_6             0.9748      0.0731      0.1418      0.0904
CANDIDATE_7             0.0748      0.1055      0.1493      0.9269
```

Ground-truth margins (correct candidate versus strongest incorrect candidate):

| fragment | correct | strongest incorrect | margin |
| --- | --- | --- | --- |
| FRAGMENT_1 | C6 = 0.9748 | C3 = 0.1106 | 0.8642 |
| FRAGMENT_2 | C0 = 0.9546 | C7 = 0.1055 | 0.8492 |
| FRAGMENT_3 | C3 = 0.9677 | C7 = 0.1493 | 0.8184 |
| FRAGMENT_4 | C7 = 0.9269 | C2 = 0.1486 | 0.7783 |

Reverse ranking: C6 peaks on FRAGMENT_1, C0 on FRAGMENT_2, C3 on FRAGMENT_3 and C7 on FRAGMENT_4,
matching the annotation exactly. Distractors {1, 2, 4, 5} never win a reference-fragment ranking.

**These margins describe one screenshot.** One representative fixture is far too little evidence to
fix production thresholds, and none are set anywhere in Stage 3.

## Robustness (deterministic perturbations of the raw crops)

Fragment pairs (minimum correct score / maximum incorrect score) at +/- 4 px tolerance:

| perturbation | correct | incorrect |
| --- | --- | --- |
| none | 0.927 | 0.149 |
| shift +2,0 / 0,-3 / -2,+3 | 0.930 - 0.948 | 0.153 - 0.210 |
| shift +3,+3 / -4,-4 / +4,0 | 0.940 - 0.955 | 0.196 - 0.238 |
| blur 5x5 sigma 1.2 | 0.936 | 0.154 |
| JPEG quality 55 | 0.928 | 0.149 |
| brightness 0.6x / 1.35x+10 | 0.916 - 0.927 | 0.149 |
| contrast 0.55x+40 | 0.927 | 0.149 |
| shift + blur + JPEG | 0.938 | 0.181 |

Under every perturbation the identified target stays FP_1 (score 0.606 - 0.633 against a runner-up
of at most 0.19) and every annotated fragment/candidate pair keeps its ranking. The test suite
asserts those rankings with deliberately loose floors so it catches real regressions rather than
normal numeric drift.

## Stage boundary

Stage 3 exposes scores and rankings. It does not select the final four candidates, does not
implement one-to-one assignment, does not apply confidence thresholds and does not produce a
`RecognitionResult`. Stage 4 consumes this ranking plus the 8x4 matrix and adds the constrained
assignment, ambiguity handling and confidence rules.

Fixture annotations are evaluation material: tests and the Stage 3 diagnostics tool read them,
production matchers never do.

## Reproduce

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.matching.evaluation.MatchingEvaluation"
```

The evaluation writes `target/stage3-target-scores.csv`, `target/stage3-fragment-score-matrix.csv`,
`target/stage3-matching-report.txt`, `target/stage3-fragment-score-heatmap.png` and
`target/stage3-fragment-match-preview.png` (build output, never committed).
