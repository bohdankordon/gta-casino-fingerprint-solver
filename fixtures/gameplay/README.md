# Gameplay fixture (Stage 2)

Representative development material for the GTA Online Diamond Casino Heist
fingerprint matching minigame. This is a representative YouTube gameplay screenshot,
NOT canonical ground truth for every installation and NOT yet user-captured footage.

## Provenance

- Source: `fixtures/gameplay/source/representative-fingerprint-minigame-2560x1440.png`
- Dimensions: exactly 2560 x 1440 pixels.
- The file was moved from the temporary `stage2-gameplay-source.png` without any byte
  changes and must remain byte-for-byte unchanged. Do not preprocess, resize, or
  re-encode it.
- SHA-256 at import time: `250B9E7FFFD9BF95D7358BFA47FE59D6EB4E49135E7731C0128BE32ED5CD2495`
- CI verifies that hash over the raw fixture bytes, so an accidental re-encoding or
  replacement is caught even when decoded dimensions still look valid.
- The ORIGINAL LOCAL file is used directly. A copy uploaded into ChatGPT appeared as
  2048 x 1152, which is a downscaled representation and is explicitly NOT used as the
  canonical Stage 2 fixture. No Stage 2 coordinate was derived from that copy.

## Layout manifest

- Manifest: `fixtures/gameplay/layout/representative-2560x1440.csv`
- Format: plain CSV with header `region_type,candidate_index,x,y,width,height`
- `region_type` is `TARGET`, `CANDIDATE` or `PANEL`; `candidate_index` is empty except
  for candidates, where it is `0..7`.
- Coordinates are fixture pixels measured directly on the local 2560x1440 image:
  - origin = top-left of the fixture image
  - `x` increases right, `y` increases downward
  - rectangles are `x/y/width/height`, fully inside 2560x1440
  - `x/y` are non-negative, `width/height` are positive
- The manifest holds exactly 1 target, candidates 0..7 with no gaps or duplicates,
  plus one optional overall hack-panel ROI used only for debugging.
- Current coordinates are valid ONLY for this fixture/layout (2560x1440, this UI
  scale, this capture pipeline). Future user-captured fixtures may differ; add new
  layout files instead of reusing these coordinates. The extractor receives a layout
  rather than hard-coding coordinates, so new layouts need no algorithm changes.

## How the ROIs were measured

Tile borders are thin (about 2 px core) light frames on a near-black background.
Border centers were located with pixel-profile scans on the real fixture: tile columns
sit on a uniform 160 px pitch and rows on the same 160 px pitch. Candidate ROIs inset
4 px inside the border centers, giving eight identical 152x152 content rects that
exclude tile borders, selector corner markers (which sit outside the borders) and
neighboring tiles.

The target ROI (1300, 196, 470x695) pads the measured ridge bounding box
(x 1324..1745, y 216..871) by about 20 px. Margin strips were verified clean (nothing
above intensity 100): the CLONE TARGET header stays above, the ACCESS ATTEMPTS panel
(frame x about 1909) stays right, edge tick marks stay outside on both sides.

## Puzzle geometry

- One large target fingerprint per round (left-center CLONE TARGET panel).
- Eight candidates form a 2-column x 4-row grid in the COMPONENTS panel.
- Candidate indexing is row-major, matching RecognitionResult candidate range 0..7:

      0 1
      2 3
      4 5
      6 7

## Observed UI states (this fixture was captured mid-interaction)

- SELECTION STATE: unselected candidates are dim/gray; selected candidates become
  bright/near-white (candidates 0, 3 and 7 are selected here).
- CURSOR STATE: the focused candidate shows four corner selector markers surrounding
  its tile (candidate 7 here); the other tiles have no markers.
- Brightness MUST NOT be interpreted as correctness: a bright candidate means the
  player selected it, not that it matches the target. Stage 2 infers no answer.

## Input facts (future automation context only, NOT implemented)

- Arrow keys move the selector, Enter selects a candidate, Tab advances after four
  selections, and opening one door may require 1 to 3 consecutive fingerprint rounds.
- The intended later workflow recognizes the target plus all 8 candidates BEFORE the
  first automated selection, so recognition never needs to re-run after selection
  brightness changes within a round. Stage 2 contains NO input automation, NO live
  capture and NO minigame detection.

## Normalization

StructuralNormalizer applies one deterministic pipeline to Stage 1 reference
targets/fragments, the gameplay target and gameplay candidates:

1. achromatic intensity = per-pixel `min` across color channels (grayscale input is
   used as-is). Ridges are achromatic gray/white in every source, so geometry is
   preserved while saturated colored UI pixels (which always have one weak channel)
   are attenuated; on achromatic content `min` matches luminance closely.
2. mild `medianBlur(3)` against single-pixel compression speckle (edge-preserving).
3. per-image 1st/99th percentile stretch to 0..255, so dim, bright and reference
   inputs share comparable contrast with no hard-coded brightness threshold.
4. aspect-preserving resize (area when shrinking, linear when enlarging) centered on
   a black canvas: FRAGMENT 128x128, TARGET 256x384, single-channel CV_8UC1.

Grayscale was chosen over binary: Otsu thresholds swing from about 27 (dim gameplay)
to about 105 (bright selected/reference) for the same ridge geometry, so binarization
would produce structurally inconsistent representations across selection states.

## Brightness robustness (test-only metric, not Stage 3 matching)

StructuralNormalizerTest normalizes each crop plus deterministic synthetic variants
(`0.55x` dimmer; `1.4x + 15` brighter via saturating OpenCV scale/offset) and compares
normalized outputs by mean absolute difference per pixel (0..255):

- gameplay target: dimmer 1.144, brighter 0.393
- dim candidate 2: dimmer 2.068, brighter 0.650
- bright candidate 0: dimmer 0.374, brighter 15.352
- reference target FP_1: dimmer 1.170, brighter 0.293
- reference fragment FP_1/1: dimmer 0.209, brighter 11.858

The two high brighter values come from hard 255-clipping of already-bright ridges:
information destroyed before normalization that no brightness-invariant pipeline can
recover. The acceptance threshold of 20.0 carries margin above the largest observed
value (15.352) while still catching a genuinely unstable pipeline.

## Regenerate and validate

From the project root on Windows (powershell):

  .mvnw.cmd -B -ntp verify
  .mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.gameplay.GameplayRoiOverlay"
  .mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.gameplay.GameplayNormalizationPreview"
  .mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.gameplay.ReferenceNormalizationPreview"

The previews write `target/stage2-gameplay-roi-overlay.png`,
`target/stage2-gameplay-normalization-preview.png` and
`target/stage2-reference-normalization-preview.png` (build output, not committed).
Open all three and confirm the target ROI holds the full print, candidate ROIs exclude
borders/markers/neighbors in row-major order, dim candidates stay visible and bright
candidates keep the same structure after normalization.
