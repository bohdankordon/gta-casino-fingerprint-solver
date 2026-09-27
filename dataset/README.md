# Reference dataset (Stage 1)

This dataset preserves clean ground-truth visual material for the GTA Online Diamond
Casino Heist fingerprint matching minigame. It contains the four known target
fingerprints and their four known matching fragments each (4 targets, 16 fragments,
20 canonical assets total).

This stage is about dataset creation and validation only. It does not recognize
gameplay screenshots and implements no matching.

## Provenance

- Source collage: `dataset/source/casino-fingerprints-reference.png`
- Dimensions: exactly 1500 x 1900 pixels.
- The file was moved from the temporary `stage1-source.png` without any byte changes
  and must remain byte-for-byte unchanged. Do not preprocess, resize, or re-encode it.
- SHA-256 at import time: `C8B67FD58ADA4D2420D4689CD47F5FCA2BB767A17B7B641F1770E59860BD5908`
- CI verifies that hash over the raw source file bytes, so an accidental re-encoding
  or replacement is caught even when decoded dimensions still look valid.

## Layout mapping

- `FP_1` = top-left panel
- `FP_2` = top-right panel
- `FP_3` = bottom-left panel
- `FP_4` = bottom-right panel

Within each panel the four reference fragments underneath the target are numbered from
left to right:

- `FRAGMENT_1`, `FRAGMENT_2`, `FRAGMENT_3`, `FRAGMENT_4`

Output layout:

```text
dataset/reference/fp_1/target.png
dataset/reference/fp_1/fragments/fragment_1.png
dataset/reference/fp_1/fragments/fragment_2.png
dataset/reference/fp_1/fragments/fragment_3.png
dataset/reference/fp_1/fragments/fragment_4.png
dataset/reference/fp_2/...
dataset/reference/fp_3/...
dataset/reference/fp_4/...
```

## Reference fragment IDs vs live candidate indices

`FRAGMENT_1..FRAGMENT_4` are canonical reference fragment identifiers for the known
matching pieces in the source collage, numbered left-to-right in each COMPONENTS strip.

They are NOT the candidate positions `0..7` used by `RecognitionResult` for a live game
puzzle. A live puzzle shows eight candidates in unknown order; exactly four of them
match the target. Reference IDs describe ground-truth pieces; candidate indices describe
positions in one observed puzzle.

## Manifest and coordinate convention

- Manifest: `dataset/layout/reference-layout.csv`
- Format: plain CSV with header
  `fingerprint_id,asset_type,fragment_id,x,y,width,height,output_path`
- `asset_type` is `target` or `fragment`; `fragment_id` is empty for targets and
  `1..4` for fragments.
- `output_path` is repo-relative with forward slashes.
- Every `output_path` must equal the canonical location implied by its record
  (`dataset/reference/fp_1/target.png`, or
  `dataset/reference/fp_2/fragments/fragment_3.png` and equivalents); normalized
  resolved paths cannot escape `dataset/reference/`, and the generator additionally
  refuses to write outside that tree.
- Coordinates are source-image pixels:
  - origin = top-left of the source image
  - `x` increases right, `y` increases downward
  - rectangles are `x/y/width/height`, fully inside the 1500x1900 source
  - `x/y` are non-negative, `width/height` are positive
- The manifest is part of the dataset contract: exactly 4 targets, 16 fragments,
  20 records total; each `FP_1..FP_4` has exactly 1 target and fragments `1,2,3,4`;
  output paths are unique.

## Canonical crops

Canonical assets are RAW CROPS:

- crop tightly and consistently around the useful fingerprint content;
- preserve original pixels, resolution and color channels;
- exclude surrounding UI and tile borders where practical.

No resizing, rotation, thresholding, binarization, grayscale conversion, sharpening,
denoising, blur, contrast changes, edge detection, morphology, or AI/ML processing is
applied. Those belong to later stages. Stage 2 will derive normalized representations
from this ground truth.

Target crops capture the fingerprint itself, not the whole `CLONE TARGET` panel.
Fragment crops capture the fingerprint piece inside its tile, not the surrounding
`COMPONENTS` panel or tile borders. One-pixel rendering differences in the source
(solid vs dotted tile borders) mean some tiles differ by 1px in outer size; interiors
are inset by 3px to exclude borders while preserving all fragment pixels with a small
black margin.

## Regenerate and validate

From the project root on Windows:

```powershell
.\mvnw.cmd compile exec:java -Dexec.mainClass=io.github.bohdankordon.casinofingerprint.dataset.ReferenceDatasetGenerator
.\mvnw.cmd -B -ntp verify
```

The default `compile exec:java` health check is unchanged:

```powershell
.\mvnw.cmd compile exec:java
```

## Preview (human verification)

Generate a labelled contact sheet showing `TARGET` plus `FRAGMENT_1..FRAGMENT_4` for
each `FP_1..FP_4`:

```powershell
.\mvnw.cmd compile exec:java -Dexec.mainClass=io.github.bohdankordon.casinofingerprint.dataset.ReferenceDatasetPreview
```

It writes `target/reference-dataset-preview.png` (build output, not committed). Open it
and check that no target is mislabeled, fragment order is left-to-right, no crop includes
neighboring tiles or unnecessary UI, and tile borders are excluded before approving.
