# Experimental native 1920x1080 live profile (Stage 8D.2A)

Status: EXPERIMENTAL. The stable production live profile is and remains 2560x1440.
The 1920x1080 profile has offline real-recording evidence but has NOT completed
independent live-PC validation. Do not claim 1080p live reliability, do not claim
Stage 8D.2 passed, and do not change the Stage 8D.1 status of the 1440p snapshot.

## Profiles

- Stable: 2560x1440, layout `fixtures/gameplay/layout/representative-2560x1440.csv`,
  `ControlThresholds.PRODUCTION_1440P` (150/150/600/45/20). Default; no flag needed.
- Experimental: native 1920x1080, layout
  `fixtures/gameplay/layout/experimental-1920x1080.csv`,
  `ControlThresholds.EXPERIMENTAL_1080P` (79/79/315/45/20). Only with the explicit
  opt-in `--enable-experimental-1080p`, and only together with `--enable-input` for
  the live solver. No other resolution exists; no frame is ever resized.

The 1080p manifest is derived EXACTLY from the 1440p manifest by the Stage 6
`EvaluationLayoutScaler` algorithm (uniform factor 0.75, rectangle edges rounded and
only then turned into width and height). The focus bounds use the exact bracket-band
area after integer rounding (band 6080 at tile 152, band 3192 at tile 114, ratio
0.525: 150 -> 79, 600 -> 315), never the naive 0.75-squared 0.5625 guess.

## Offline evidence carried over

- Positive 1080p frames: 2255 (CURRENT_ROUND_MATCH 2188, PREVIOUS_ROUND_CARRYOVER 35,
  UNEXPLAINED_MISMATCH 0, UNCERTAIN 32).
- Strict-negative 1080p gameplay: 585 sampled plus 3513 exhaustive, 0 false recognized.
- All four annotated 1080p rounds reached a stable correct answer.
- Control states: 24/24 committed 1080p annotation rows passed.

## Invocations

Recognition-only preflight (sends no input, useful first check on the second PC):

```powershell
LiveRecognitionMain --monitor 0 --once --enable-experimental-1080p
LiveRecognitionMain --monitor 0 --watch --enable-experimental-1080p
```

Experimental live solver (double opt-in; sends real input):

```powershell
LiveSolverMain --watch --enable-input --enable-experimental-1080p --target-exe GTA5_Enhanced.exe --abort-key SCROLL_LOCK --monitor 0
```

Monitor listing is input-free and needs no opt-in; each monitor reports whether its
PHYSICAL mode matches stable 1440, experimental 1080, or neither.

The repository helper `scripts/run-experimental-1080p.ps1` (run from the repository
root after cloning) refuses a dirty tree, runs `clean verify` first, saves every log
below `target/stage8d-1080p-live/<timestamp>/`, and requires
`-ConfirmExperimentalLiveInput` for the live mode:

```powershell
scripts/run-experimental-1080p.ps1 -ListMonitors
scripts/run-experimental-1080p.ps1 -RecognitionOnly -Monitor 0
scripts/run-experimental-1080p.ps1 -Live -Monitor 0 -ConfirmExperimentalLiveInput
```

## Recording requirements for the validation run

- GTA V Enhanced, Borderless, native physical 1920x1080.
- Full uncropped 16:9 game output, preferably 60 fps (30 fps acceptable).
- No picture-in-picture, no webcam, no overlay covering the fingerprint UI.
- Start about 5 seconds before entering the fingerprint terminal.
- Record all rounds; continue about 5 seconds after exiting the fingerprint UI.
- If a human/manual correction happens, record it in the run notes.
- For an automated run, do not manually press arrows, Enter or Tab once the solver owns a round.
- Emergency abort: SCROLL_LOCK if verified unbound on that PC. Ctrl+C stops the watcher itself.

No recording file is copied into the repository or committed.

## Validation sequence (after independent review, before any automated 1080p hack)

A. `clean verify` on the exact reviewed commit.
B. Monitor listing: confirm the selected physical mode is exactly 1920x1080.
C. Recognition-only watch OUTSIDE the puzzle: expect no stable false recognition.
D. Recognition-only first fingerprint observation: manually inspect the reported answer if practical.
E. Production input single-tap smoke at 1080p (input delivery is resolution-independent,
   but the foreground/desktop environment is new): harmless `InputDiagnosticMain UP` and
   `InputDiagnosticMain SELECT` with an explicit SCROLL_LOCK in a harmless GTA UI.
F. Only then: the experimental 1080p live solver while screen recording is active.
