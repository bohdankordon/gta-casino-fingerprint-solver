# Live recognition runtime (Stage 5)

Stages 1 to 4 provide the recognition-only core: a gameplay frame is reduced to fixed-layout
ROIs, normalized, matched against the reference dataset and turned into a conservative
recognition decision. Stage 5 connects that core to the live Windows desktop.

    captured desktop frame
      -> HiDPI-aware physical-resolution selection
      -> frame-size validation against the layout
      -> ROI extraction -> structural normalization -> PuzzleRecognitionEngine
      -> conservative RecognitionDecision per frame
      -> decision consensus over consecutive frames
      -> recognition-only command-line output

Scope: Stage 5 reads the screen and prints recognition results. It sends NO input. There is no
keyboard simulation, no mouse input, no selected-tile navigation, no Enter or Tab handling, no
automatic solving, no round state machine, no game-process manipulation, no injection, no overlay,
no OCR, no machine learning and no network service in this stage.

## Capture backend

The backend is capture.AwtScreenCapture: java.awt.Robot plus the HiDPI-aware
Robot.createMultiResolutionScreenCapture facility. capture.ScreenCapture stays the abstraction
boundary (the caller owns the returned Mat), so a future Windows Desktop Duplication or Windows
Graphics Capture backend can replace AWT without touching the recognition core. No such backend is
implemented speculatively in this stage.

One capture performs these steps:

1. take the selected device's LOGICAL bounds from its default configuration and its PHYSICAL
   display mode from the device itself;
2. request a multi-resolution capture of the logical rectangle;
3. inspect every returned resolution variant;
4. convert only the variant whose pixel size is EXACTLY the required physical resolution.

The converted frame is CV_8UC3 BGR, produced by capture.BufferedImageMatConverter: the image is
brought to a plain TYPE_3BYTE_BGR layout (whose byte order already is B, G, R) through the AWT
raster pipeline, its backing byte array is wrapped in a temporary Mat header and cloned, so no Java
per-pixel getRGB loop runs over a full 2560x1440 screenshot. The returned Mat owns a private copy
of the pixels and never references the BufferedImage. A conversion holds two short-lived
resources - a native byte pointer holding a copy of the image bytes and a Mat header that borrows
it - and both are closed in a fixed order before the conversion returns, the header before the
pointer. Roughly 11 MB of temporary native storage per 2560x1440 frame is therefore released
deterministically instead of waiting for garbage collection, which matters in a watch loop where
that happens every captured frame.

When the platform cannot provide HiDPI variants (createMultiResolutionScreenCapture throws
UnsupportedOperationException), the backend falls back to a plain single-image capture. That
fallback is still exact: a single image is accepted only when it already carries the required
physical resolution, and rejected by the same variant selection otherwise. Unscaled pixels are
never invented.

Ownership: every returned Mat is newly allocated and owned by the caller. No BufferedImage of a
captured frame is retained after a capture returns, so a long watch run does not accumulate screen
images.

## Windows display scaling (logical vs physical)

A physical 2560x1440 display can expose a smaller LOGICAL desktop coordinate space when Windows
display scaling is enabled: at 125% scaling the same panel reports 2048x1152 logical bounds. The
two are not interchangeable. The Stage 2 ROI coordinates are physical pixels of the recorded
layout, so a logical-resolution image would move every region, and the recognition result would be
meaningless long before it was visibly wrong.

Stage 5 therefore never resizes a capture into the layout:

- the required resolution comes from the layout manifest itself (the bundled representative layout
  is 2560x1440) and is passed to the capture backend;
- the backend records the logical bounds and the physical display mode before capturing;
- every returned variant is inspected;
- the variant whose pixels match the required physical resolution EXACTLY is selected;
- if no variant matches, the capture fails with UnsupportedResolutionException.

The failure diagnostic contains the monitor index and device id, the logical bounds, the physical
display mode and the dimensions of every variant that was actually returned:

    No capture variant matches the required physical resolution 2560x1440.
      monitor         : 0 (\\.\DISPLAY1)
      logical bounds  : 0,0 2048x1152
      display mode    : 2560x1440 @ 59.9 Hz
      capture variants: [2048x1152]
      This is the Windows display-scaling case: a logical-resolution image is never
      resized into the physical gameplay layout, because that would shift every ROI.

A frame that a backend nevertheless delivers at the wrong size (for example a logical 2048x1152
capture) is rejected before ROI extraction and reported as UNSUPPORTED_FRAME, with its own
diagnostic naming both sizes and the scaling cause.

## Monitors

capture.AwtMonitorEnumerator describes each attached GraphicsDevice as a capture.MonitorInfo:
stable runtime index, device id, primary flag, logical bounds and physical display mode with the
refresh rate when the platform reports one. Monitor enumeration only reads desktop metadata; it
never captures anything.

capture.MonitorSelector resolves what to capture:

- an explicit --monitor <index> is honoured exactly, and is rejected when that monitor's PHYSICAL
  display mode is not the required resolution;
- automatic selection happens only when exactly one monitor matches;
- zero matches and several matches both fail with a diagnostic that lists the attached monitors
  and the requirement, and several matches ask for an explicit --monitor <index>.

A monitor is never chosen silently, and matching always uses the physical display mode: a monitor
whose LOGICAL bounds happen to be 2560x1440 while its panel runs another physical mode is not a
match.

## Supported resolution

The only validated gameplay layout is the bundled representative 2560x1440 manifest
(fixtures/gameplay/layout/representative-2560x1440.csv). The runtime reads that manifest, requires
its physical frame size exactly, and reports UNSUPPORTED_FRAME for anything else. Other
resolutions or UI scales are NOT supported, and no claim of arbitrary-resolution support is made:
each would need its own measured layout manifest.

## One-frame pipeline

runtime.FrameRecognitionPipeline turns one full captured screen into a recognition decision and is
built once per session:

    full screen Mat
      -> frame dimensions validated against the layout (rejected before extraction if wrong)
      -> GameplayFrameExtractor (target + eight candidates)
      -> StructuralNormalizer (256x384 target, 128x128 candidates)
      -> PuzzleRecognitionEngine (target matching, fragment matching, constrained assignment)
      -> RecognitionDecision

Ownership is explicit: the caller keeps the full frame, which is never modified and never closed
by the pipeline; the extracted and normalized intermediates are closed by the pipeline itself,
including on failure. The reference library (matching.ReferenceFingerprintLibrary) is loaded ONCE
per runtime session, is owned by the runtime and is closed once on shutdown; the pipeline borrows
it and never closes it.

## Consensus over consecutive frames

One recognized frame is not a live result. runtime.RecognitionConsensusTracker requires
consecutive frames with the same answer - the same fingerprint and the same sorted set of selected
candidate indices - before the runtime reports a stable recognition:

    frame 1  FP_1 [0, 3, 6, 7]  -> CANDIDATE_RECOGNITION 1/3
    frame 2  FP_1 [0, 3, 6, 7]  -> CANDIDATE_RECOGNITION 2/3
    frame 3  FP_1 [0, 3, 6, 7]  -> STABLE_RECOGNIZED 3/3

Reset rules are fail-closed:

- an UNCERTAIN frame ends the streak; FP_1, UNCERTAIN, FP_1 is NOT three consecutive frames;
- a different recognized answer starts a new streak of one for that answer;
- a frame that produced no decision at all (UNSUPPORTED_FRAME, CAPTURE_ERROR) also ends the
  streak, because the required frames must be consecutive;
- evidence and confidence may drift between frames without breaking a streak: they measure the
  same answer on slightly different pixels.

The required number of consecutive frames is configurable (--stable-frames) and defaults to the
conservative value 3.

Decision consensus was chosen over a raw pixel-difference threshold on purpose. A pixel threshold
would need calibration data from real live gameplay frames that do not exist yet; consecutive
independent recognitions provide the same fail-closed temporal gate without an unjustified image
constant. No probabilistic temporal filtering is used.

## Runtime states

runtime.LiveRecognitionState describes what the runtime knows after a frame:

| state | meaning |
| --- | --- |
| WAITING | nothing has been captured yet |
| UNSUPPORTED_FRAME | the captured frame is not the supported physical layout size; no decision |
| CAPTURE_ERROR | the capture backend failed for this frame; no frame, no decision |
| UNCERTAIN | a frame was recognized but stayed uncertain |
| RECOGNIZED | one recognized frame, not consensus-confirmed (--once mode) |
| CANDIDATE_RECOGNITION | consecutive identical answers below the required count |
| STABLE_RECOGNIZED | the required number of consecutive identical answers was reached |

No state is a gameplay command. A state says what was seen, never what to press.

UNCERTAIN is not "puzzle absent". A random screen and a real but currently uncertain puzzle both
produce UNCERTAIN, and Stage 5 deliberately does not invent a brittle UI presence detector from a
single gameplay fixture. A dedicated presence detector can be evaluated later, with more
user-captured data.

## Command line

On Windows (PowerShell), with the bundled Maven wrapper:

    # enumerate monitors with logical bounds and physical display mode
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--list-monitors"

    # capture and recognize exactly one frame
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--monitor 0 --once"

    # watch the desktop; a result is stable after three consecutive identical answers
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--monitor 0 --watch"
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--monitor 0 --watch --interval-ms 300 --stable-frames 4"

Options:

| option | meaning |
| --- | --- |
| --list-monitors | list attached monitors and exit |
| --monitor <index> | capture this monitor; required when several monitors match |
| --once | capture one frame and print its decision (never reported as stable) |
| --watch | capture repeatedly and report consensus results |
| --interval-ms <ms> | watch capture interval, default 200 |
| --stable-frames <n> | consecutive identical answers required, default 3 |
| --help | usage |

Exit codes: 0 when the requested run completed (an UNCERTAIN frame is a completed run), 2 for
command-line errors, 3 when monitor selection, the reference library or the capture failed.

--once prints one decision and explicitly not a stable result:

    status: RECOGNIZED
    fingerprint: FP_1
    candidates: [0, 3, 6, 7]
    evidence: 0.6102
    note: a single frame is never reported as stable; --watch requires 3 consecutive identical answers.
    timing: capture 65.3 ms, extraction+normalization 24.2 ms, recognition 41.4 ms, total 130.9 ms

--watch prints state changes only, so a stable answer is printed once instead of on every frame;
a heartbeat line appears every 60 frames and Ctrl+C ends the run with a frame count and average
timings. Nothing is ever sent back to the desktop.

## Manual capture probe

capture.CaptureProbe is the manual Windows smoke test:

    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.capture.CaptureProbe"
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.capture.CaptureProbe" "-Dexec.args=--monitor 0"

It prints the monitor list, the selected monitor, the logical bounds, the physical display mode,
every returned capture variant, the selected variant, and the width, height, type and channel count
of the final Mat, and it saves the frame as target/stage5-capture-probe.png. The image is local
debug output: it is never committed, never uploaded and never attached to a pull request.

The probe is a manual tool. Continuous integration has no desktop, so no automated test captures a
real screen.

Result on the development machine (Windows, single 2560x1440 primary display at 100% scaling):

    [0] \Display0 primary logical 0,0 2560x1440 physical 2560x1440 @ 165.0 Hz
    capture variants : [2560x1440]
    selected variant : 2560x1440
    width 2560, height 1440, CV_8UC3, 3 channels

Because that display runs without scaling, only one variant exists there and the multi-variant
path is covered by deterministic unit tests (an exact 2560x1440 variant wins over a 2048x1152
logical one; a logical-only capture is rejected instead of resized) rather than by a live scaled
capture.

## Performance

Rough single-frame measurements on the development machine, taken from the runtime itself (--once
and the --watch summary):

| phase | first (cold) frame | steady state |
| --- | --- | --- |
| screen capture | about 1.4 s | 50-74 ms |
| ROI extraction + normalization | 24-27 ms | about 10-27 ms |
| recognition (target + fragments + assignment + policy) | 41 ms | about 18-41 ms |
| total | about 1.5 s | 80-140 ms |

The cold capture includes Robot initialization. A 200 ms watch interval is comfortably achievable
for a recognition-only pass; no optimization was attempted and the Stage 3 matching and Stage 4
policy algorithms are unchanged by this stage.

## Tests

The Stage 5 tests are deterministic and need no desktop:

- capture: BufferedImage to Mat conversion (CV_8UC3 BGR, known-pixel channel order, independent
  pixel ownership), resolution-variant selection (exact physical variant preferred, logical-only
  capture rejected with its diagnostic) and monitor selection (single match, zero matches,
  several matches, explicit index, unknown index);
- pipeline: the stored 2560x1440 fixture is recognized as FP_1 [0, 3, 6, 7] with the same evidence
  as the Stage 4 path, the source frame is not modified, wrong frame sizes are rejected before
  extraction, repeated recognition is deterministic and one library serves repeated frames;
- consensus: streak counting, stable threshold, UNCERTAIN reset, different-answer reset,
  evidence drift without reset, deterministic stable state and required-frame validation;
- runtime: a fake ScreenCapture drives three fixture frames to one stable recognition, a fixture /
  decoy / fixture sequence never becomes stable, a logical-size capture yields UNSUPPORTED_FRAME
  without any candidate selection, and a backend failure yields CAPTURE_ERROR without breaking the
  loop;
- boundary: a reflective guard over the Stage 5 public surface plus the fixed runtime state list,
  so an input API or an input state cannot be added silently.

Everything AWT-dependent (Robot capture, GraphicsDevice resolution) stays behind
MonitorEnumerator and CaptureFactory, which in-memory fakes replace in tests; the real backend is
exercised only by the manual probe.

## Resource ownership

- ScreenCapture: the caller owns the returned Mat.
- LiveRecognitionRuntime: closes the captured frame at the end of every iteration, including
  failures; keeps no frame between iterations.
- FrameRecognitionPipeline: owns the extractor, normalizer and engine; closes the extracted and
  normalized intermediates; borrows the reference library.
- ReferenceFingerprintLibrary: loaded once per runtime session, closed once on shutdown.
- AWT: no BufferedImage of a captured frame is retained after a capture call.
- BufferedImageMatConverter: the temporary native pixel buffer and the borrowing Mat header of a
  conversion are closed before the call returns; no conversion object needs garbage collection to
  release them.

## Limitations

- Only the 2560x1440 gameplay layout is supported. A smaller logical capture is never scaled into
  it, and other resolutions need their own measured manifest before they can be used.
- Robot capture is not validated against the game. Exclusive-fullscreen modes can produce black or
  stale frames for some driver and overlay combinations; borderless or windowed
  desktop-composited modes are the expected first environment. If real-game testing shows that
  Robot cannot capture a particular fullscreen mode, a different ScreenCapture backend can be
  added without changing the recognition core.
- Live GTA validation is still outstanding. It requires a user sitting in front of the fingerprint
  minigame; nothing in this stage claims to have seen a real puzzle frame.
- No puzzle presence detector: UNCERTAIN covers both "no puzzle here" and "puzzle not confirmed".

## Stage boundary

Still NOT implemented and not started: keyboard input, key simulation, arrow-key navigation, Enter
or Tab handling, mouse input, selected-tile navigation, automatic puzzle solving, multi-round
automation, game-process manipulation, DLL injection, hooks and injected overlays. Stage 6
(robustness evaluation and tuning against user-captured fixtures) has not started.
