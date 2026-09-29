# Guarded single-tap input diagnostics (Stage 8C.1)

Stage 8C.1 adds ONE deliberately tiny, Windows-only diagnostic CLI whose only job is to
validate the already-implemented Stage 7B native input layer against the user's real GTA
installation by sending AT MOST ONE explicitly requested gameplay key tap per invocation.

The tool is NOT the solver. It performs no recognition, no screen capture, no round
lifecycle work, no navigation planning and no automatic repetition. It exists so that the
keyboard layer can be observed in the real game, by a human, before any end-to-end vault
run is attempted.

Nothing in Stage 8C.1 has been validated against real GTA input yet: this document
describes the implementation and the manual procedure the user performs after review and
merge. No real tap has been sent during implementation or testing.

## Prerequisite: Stage 8B capture validation (complete)

Stage 8B validated real GTA capture in Borderless mode. Measured result:

- physical resolution: 2560x1440;
- frame format: CV_8UC3;
- 282 continuous frames captured;
- unsupported frames: 0;
- capture errors: 0;
- average warmed capture: 43.4 ms per frame.

No Windows Graphics Capture or Desktop Duplication backend is needed at this time. Stage
8C.1 does not re-measure, change or depend on capture: the diagnostic never captures a
frame and reuses no capture class.

## Exactly-one-tap invariant

One invocation may emit AT MOST ONE complete key tap (key down plus key up).

- The countdown phase never sends input.
- The tap happens only after every gate below has passed.
- There is no retry: a tap that throws is reported as INPUT ERROR and never repeated.
- There is no second tap: the run returns immediately after the single `sink.tap(...)`
  call, and nothing sleeps afterwards.
- There is no loop, no repetition count and no automatic follow-up invocation. Each
  invocation is one process, one request, one possible tap.

## Allowed controls

Only five controls are accepted:

| `--control` | key sent |
| --- | --- |
| `UP` | arrow up |
| `DOWN` | arrow down |
| `LEFT` | arrow left |
| `RIGHT` | arrow right |
| `SELECT` | Enter |

## PROCEED / Tab is forbidden

`--control PROCEED` is refused in Stage 8C, in every spelling, before any input backend
exists; the process exits with the usage refusal and sends zero input. There is no alias of
any kind that exposes Tab. The refusal is enforced twice (CLI option parsing and the core
runner) and is pinned by tests, including a test that no allowed control maps to the Tab
virtual-key code.

Advancing the round is what Stage 7B's guard rails exist for; Stage 8C validates single
harmless keys only.

## CLI contract

```
InputDiagnosticMain --enable-input --target-exe <exact.exe> --control <UP|DOWN|LEFT|RIGHT|SELECT>
                    [--countdown-seconds <n>]
InputDiagnosticMain --help
```

Input requires ALL of:

- Windows;
- the explicit `--enable-input` opt-in;
- an exact `--target-exe` executable file name (for example `GTA5.exe`, not a path);
- exactly one allowed `--control`.

Missing any one, an unknown option, a duplicate value option, a malformed countdown or a
countdown outside 1..30 seconds refuses with zero input. Running the tool with no arguments
refuses as well: there is no default mode that sends anything.

Exit codes: 0 tap sent (or `--help`), 2 usage refusal, 3 refused with zero input.

## Countdown and F12 abort

`--countdown-seconds` defaults to 5 seconds and is bounded to 1..30. The countdown exists
so the operator can switch (Alt+Tab) into GTA after starting the command.

While the countdown runs, the CLI prints:

```
DIAGNOSTIC INPUT ARMED
DIAGNOSTIC requested control: UP
DIAGNOSTIC target executable: GTA5.exe
DIAGNOSTIC exactly ONE tap maximum
DIAGNOSTIC F12 aborts
DIAGNOSTIC switch to GTA now; countdown: 5 s before the foreground gates
```

The countdown sleeps in fixed 100 ms poll steps and checks the F12 emergency abort
(`WindowsEmergencyAbort`, the same read-only `GetAsyncKeyState` poll Stage 7B uses) after
every step. No input is sent during the countdown.

If F12 is active at any point - before the countdown, during it, after the foreground pin,
or immediately before the tap - the run refuses with ABORTED, sends zero input and exits.
One invocation ends after an abort; nothing resumes and nothing is retried.

## Target executable foreground pin

After the countdown the diagnostic pins the CURRENT foreground target using
`WindowsForegroundTargetGuard.pin(targetExe)`: the exact window handle (HWND), the
process id and the executable file name. The pin succeeds only when the foreground window
already belongs to the configured executable; the tool never activates, focuses or
launches the game itself.

If the pin fails, the run refuses with TARGET NOT FOREGROUND and sends zero input. If the
pinned target no longer owns the foreground window immediately before the tap, the run
refuses with FOCUS LOST and sends zero input.

This is read-only operating-system metadata (`GetForegroundWindow`,
`GetWindowThreadProcessId`, `QueryFullProcessImageName`) through the unchanged Stage 7B
classes: no process-memory access, no injection, no hooks, no drivers.

## Final pre-tap order

The order is explicit in `SingleTapInputDiagnostic.run` and pinned by tests:

```
require Windows
require explicit input opt-in
refuse PROCEED (Tab is never sent)
countdown while polling the F12 abort (no input)
abort idle
pin the current foreground target of the exact executable
abort idle
foreground pin still valid (same HWND, pid, executable)
abort idle AGAIN immediately before the tap
sink.tap(control) exactly once
return (no sleep, no retry, no second tap)
```

## No recognition, no solver, no repetition

The diagnostic references none of the solver stack: no `FrameRecognitionPipeline`, no
`DryRunSolveOrchestrator`, no `LiveSolveOrchestrator`, no `DryRunPlanner`, no
`GuardedPlanExecutor`, no `GameplayLayout` and no `ScreenCapture`. A source-isolation
test enforces this and also proves that the Windows input backends are constructed in
exactly one place, behind the non-Windows gate.

The reusable core is `SingleTapInputDiagnostic` behind the existing Stage 7B contracts
(`GameInputSink`, `ForegroundTargetGuard`, `AbortSignal`). No new SendInput
implementation exists, and the virtual-key mapping is unchanged. All automated tests use
fakes: CI can never emit keyboard input.

## Safe manual validation procedure (after review and merge)

Each step is a separate invocation, started from PowerShell in the repository root, with
the repository compiled first (` .\mvnw.cmd -B -ntp compile`). One invocation sends at
most one tap; never batch controls and never test Tab in Stage 8C.

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.InputDiagnosticMain" "-Dexec.args=--enable-input --target-exe GTA5.exe --control UP --countdown-seconds 5"
```

Replace `GTA5.exe` with the exact executable file name of the installation and `UP` with
one allowed control.

A. Negative focus test
   - start the invocation and do NOT foreground GTA;
   - expected: zero input, `TARGET NOT FOREGROUND`, exit code 3.

B. F12 abort test
   - foreground GTA, hold F12 during the countdown;
   - expected: zero input, `ABORTED`, exit code 3; the single invocation ends there.

C. Arrow tests
   - manually open a harmless GTA UI (for example the pause map or the phone);
   - one invocation per control: `UP`, then `DOWN`, then `LEFT`, then `RIGHT`;
   - expected per invocation: exactly one visible movement step.

D. SELECT test
   - manually prepare a harmless menu item where Enter has a harmless visible effect;
   - one `SELECT` invocation;
   - expected: exactly one visible action, nothing else.

Success output is:

```
DIAGNOSTIC SENT exactly one UP tap to pinned GTA5.exe target
DIAGNOSTIC no further input will be sent
DIAGNOSTIC diagnostic complete
```

The tool never claims that GTA reacted correctly: the human observing the game validates
the visible response.

## What this stage does NOT claim

- No real input validation has passed yet. Stage 8C.1 is implementation plus a documented
  manual procedure; the manual results are produced by the user after merge.
- Stage 8C is NOT complete.
- Stage 8D (any end-to-end vault testing) remains blocked until the human-observed Stage 8C
  results pass.
