# Stage 6 real gameplay recording annotations

Round-level ground truth for two independent real GTA Online Diamond Casino Heist
screen recordings, plus the technical metadata of the recordings themselves.

These files are annotations only. The recordings are PRIVATE LOCAL material: they
live in the ignored `local-data/stage6/` directory, they are never committed, never
copied into fixtures or test resources, never attached to a pull request and never
uploaded. This directory contains no frame, no contact sheet, no audio and no video.

## Files

| file | content |
| --- | --- |
| `stage6-sources.csv` | generic source id, plain file name, container, resolution, fps, decoded frame count, derived duration, byte size, SHA-256 |
| `stage6-hack-windows.csv` | the user-provided approximate hack windows, used only to define strict negative gameplay |
| `stage6-rounds.csv` | every annotated round: approximate interval, target fingerprint, correct candidate per reference fragment, sorted correct candidate set, flags and notes |

## Provenance

- The round-level ground truth was established by hand from the recordings,
  independently of the recognition system.
- Recognition output was NEVER used to create or modify these labels, and no label
  was derived from a matcher score, a policy decision or a consensus result.
- The recordings contain the complete vault gameplay segment, not only the
  fingerprint minigame: ordinary gameplay, several fingerprint door hacks, multiple
  rounds per hack, selected and unselected candidate states, selector movement,
  round transitions and at least one real wrong selection with a game-side ERROR.
- The interval boundaries are approximate by roughly a few tenths of a second. They
  are deliberately NOT frame-exact assertions: the benchmark walks every frame of an
  interval instead of trusting one chosen moment.
- `stage6-hack-windows.csv` is coarser than the round intervals. A round may start
  marginally before its approximate hack window (one round does, and the benchmark
  reports it); the windows exist only to decide which gameplay counts as strict
  negative material.

## Format

`stage6-rounds.csv`

    source_id,resolution,hack_id,round_id,start_seconds,end_seconds,target_fingerprint,
    fragment_1_candidate,fragment_2_candidate,fragment_3_candidate,fragment_4_candidate,
    correct_candidates,contains_wrong_selection,notes

- `correct_candidates` is the sorted correct candidate set as `;`-separated indices,
  for example `1;4;5;6`. It must agree with the four `fragment_*_candidate` values.
- `contains_wrong_selection` marks a round in which the player really selected a
  wrong candidate and the game answered with ERROR.
- Candidate indices are the row-major grid indices of the recognition contract:

      0 1
      2 3
      4 5
      6 7

`stage6-hack-windows.csv`

    source_id,hack_id,start_seconds,end_seconds,notes

`stage6-sources.csv`

    source_id,file_name,container,width,height,fps,frames_decoded,duration_seconds,
    size_bytes,sha256

- `duration_seconds` is derived as `frames_decoded / fps`. Container headers can
  disagree slightly: the Matroska header reports 212.200 s and the MP4 header reports
  208.699 s for the same content the decoder walks in 209.139 s of frame timestamps,
  because the MP4 carries a non-zero first-frame timestamp.
- `frames_decoded` is the number of frames a full sequential decode produced. The MP4
  header advertises 6052 frames while 6065 frames decode; the benchmark reports both
  numbers instead of silently trusting the header.
- The SHA-256 is the integrity contract of the dataset: the benchmark verifies size
  and hash before measuring anything and refuses to run when they differ.

## Dataset

| source id | resolution | hacks | rounds |
| --- | --- | --- | --- |
| `recording_1440p` | 2560x1440 | 2 | 4 |
| `recording_1080p` | 1920x1080 | 2 | 4 |

Annotated rounds:

| source | round | interval | target | F1 | F2 | F3 | F4 | correct set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| recording_1440p | H1R1 | 17.500 - 33.750 | FP_4 | C6 | C5 | C1 | C4 | 1;4;5;6 |
| recording_1440p | H1R2 | 34.000 - 49.250 | FP_3 | C6 | C5 | C4 | C1 | 1;4;5;6 |
| recording_1440p | H2R1 | 119.250 - 129.250 | FP_1 | C3 | C4 | C1 | C6 | 1;3;4;6 |
| recording_1440p | H2R2 | 129.750 - 138.250 | FP_3 | C7 | C6 | C4 | C2 | 2;4;6;7 |
| recording_1080p | H1R1 | 40.250 - 57.750 | FP_3 | C1 | C3 | C6 | C0 | 0;1;3;6 |
| recording_1080p | H1R2 | 58.250 - 83.250 | FP_1 | C6 | C4 | C7 | C0 | 0;4;6;7 |
| recording_1080p | H2R1 | 133.500 - 151.250 | FP_3 | C6 | C3 | C7 | C1 | 1;3;6;7 |
| recording_1080p | H2R2 | 151.500 - 169.000 | FP_4 | C2 | C7 | C1 | C6 | 1;2;6;7 |

## Fingerprint coverage of these recordings

| fingerprint | rounds |
| --- | --- |
| FP_1 | 2 |
| FP_2 | 0 |
| FP_3 | 4 |
| FP_4 | 2 |

`FP_2` has NO real-game coverage in this dataset. These recordings therefore say
nothing about FP_2 robustness, and the absence of FP_2 failures here must not be read
as evidence that FP_2 works.

## The real wrong-selection round

`recording_1080p` hack 1 round 2 contains a genuine player mistake:

- the player selected `CANDIDATE_5` incorrectly,
- the game displayed `ERROR`,
- `C5` cleared again while the other selected correct candidates stayed selected,
- the correct `CANDIDATE_0` was selected afterwards.

The annotated answer stays `FP_1` with the correct set `0;4;6;7`. The wrong-selection
state is NOT ground truth for the answer, and the ERROR-era frames are deliberately
kept inside the benchmarked interval instead of being excluded.

## Validation

The committed annotation is validated every time it is read, by
`RecordingAnnotationCatalog` and by `RecordingAnnotationCatalogTest`:

- exactly 8 rounds and 4 hack windows;
- every round maps reference fragments 1..4 to four unique candidates in 0..7;
- the sorted `correct_candidates` set agrees with the fragment mapping;
- intervals are ordered and do not overlap inside a source;
- the annotated resolution matches the source metadata;
- every annotated hack has a hack window.

A hand edit that breaks any of these invariants fails the build instead of silently
changing what the benchmark measures.

## Privacy

- No recording, no extracted frame, no contact sheet and no ROI overlay is committed.
- The benchmark writes every image it generates below `target/`, which is ignored.
- No personal user name and no absolute local path appears in this directory.
