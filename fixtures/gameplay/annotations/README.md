# Representative fixture annotations (ground truth)

Human-verified ground truth for
`fixtures/gameplay/source/representative-fingerprint-minigame-2560x1440.png`.

## Provenance

- The labels were assigned by visually comparing the gameplay crops against the canonical
  Stage 1 reference targets and fragments, from **fingerprint ridge geometry**: ridge
  orientation, ridge endings, bifurcations, loop and chevron shapes and the way each ridge
  continues across a fragment boundary.
- The labels are **independent of UI brightness and selection state**. In this fixture
  candidates 0, 3 and 7 are bright (selected) and candidate 6 is dim (unselected), yet the
  correct set is {0, 3, 6, 7}: a bright candidate means the player selected it, never that it
  matches the target.
- The labels cover **this fixture only**. They are not a general truth about the minigame and
  must not be used to derive matcher parameters, offsets, weights or bonuses.

## File format

`representative-2560x1440.csv` is plain CSV with header
`record_type,fingerprint_id,fragment_id,candidate_index`:

- one `target` record declaring which reference fingerprint this fixture shows
  (`fragment_id` and `candidate_index` empty);
- one `fragment` record per reference fragment id `1..4`, giving the candidate index `0..7`
  that is the correct match for that fragment, using the row-major indexing defined in
  `fixtures/gameplay/README.md`.

Reference fragment ids (`1..4`) are canonical Stage 1 identifiers; candidate indices (`0..7`)
are positions in this one observed puzzle. The two namespaces must not be mixed.

## Usage

This is evaluation and test material only:

- tests and the Stage 3 diagnostics tool read it to measure matcher quality;
- production matching code never reads annotation files, and no score, offset, weight or
  threshold may depend on these labels.
