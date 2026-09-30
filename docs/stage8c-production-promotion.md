# Stage 8C.4 production promotion: validated scan-code input

Stage 8C.4 promotes the manually validated SCANCODE_BATCH representation to production
(WindowsSendInputSink) and makes the live emergency abort key explicit and configurable
(EmergencyAbortKey plus abort-key). No new delivery mechanism is introduced: the only
representation change is virtual-key to Set-1 scan-code, in one batch, with no hold.

Stage 8C is NOT complete: the production-backend smoke (UP plus SELECT through the actual
promoted sink) is still pending human observation. Stage 8D remains blocked.

## Complete manual characterization (user-reported, GTA5_Enhanced.exe)

Target GTA5_Enhanced.exe, BattlEye enabled and unchanged, no hooks, no injection,
no process-memory access.

- Production VK_BATCH UP: Windows SENT, GTA no visible response.
- Production VK_BATCH SELECT: Windows SENT, GTA no visible response.
- Notepad LEFT with the same backend: PASS.
- Notepad SELECT with the same backend: PASS.
- SCANCODE_BATCH UP: PASS, exactly one visible action.
- SCANCODE_BATCH DOWN: PASS, exactly one visible action.
- SCANCODE_BATCH LEFT: PASS, exactly one visible action.
- SCANCODE_BATCH RIGHT: PASS, exactly one visible action.
- SCANCODE_BATCH SELECT: PASS, exactly one visible action.
- SCANCODE_HOLD 50 ms UP: PASS.
- SCANCODE_HOLD 50 ms SELECT: PASS.
- Dedicated Tab probe SCANCODE_BATCH (Set-1 0x0F non-extended, one batch, no hold):
  SENT, key-down yes, key-up yes, GTA pause-map Point Of Interest action exactly once: PASS.
- SCROLL_LOCK abort: ABORTED, zero input: PASS.

Human validation therefore exists for all six production controls (UP, DOWN, LEFT, RIGHT,
SELECT, PROCEED) using SCANCODE_BATCH.

## Why SCANCODE_BATCH won

The generic SendInput path works (Notepad controls PASS), so the failure was specific to
GTA Enhanced handling of the virtual-key representation. The scan-code representation is
the material difference: every required control passes as a no-hold batch once identified
by Set-1 make code with the correct extended bit. A non-zero hold is NOT required.

## Why hold is not promoted

The no-hold batch already works for every required control and is simpler: no timing
parameter, no sleep, no second submission, no repeat risk. Production keeps the proven
one-batch shape and gains no hold of any kind. A 50 ms delay was deliberately not added.

## Production mapping (all six controls)

- UP: scan 0x48, extended yes.
- DOWN: scan 0x50, extended yes.
- LEFT: scan 0x4B, extended yes.
- RIGHT: scan 0x4D, extended yes.
- SELECT: scan 0x1C, extended no (main Enter; keypad Enter shares 0x1C but is E0).
- PROCEED: scan 0x0F, extended no (main Tab, never VK_TAB).

Every event carries wVk zero (SCANCODE ignores wVk). Only arrows carry EXTENDEDKEY.
Releases add KEYUP to the same scan and extended identity. The mapping lives in production
(WindowsGameKeySpec) and matches the validated characterization byte-for-byte, pinned by
tests, without production importing input.diagnostic.

## One-batch contract

INPUT 0 is key-down, INPUT 1 is key-up, one SendInput call with 2 events. No hold,
no sleep, no timing parameter, no repeat, no second logical tap.

## Partial-delivery cleanup

If SendInput returns fewer than 2 events, production attempts ONE best-effort key-up with
the SAME scan-code identity (same wScan, same extended bit, SCANCODE plus KEYUP, wVk zero),
then throws GameInputException stating delivered N of 2, complete tap not confirmed, and
best-effort key-up attempted. The key-down is never retried, the full batch is never resent,
no second logical tap is sent. A native submission failure takes the same single cleanup
path. GuardedPlanExecutor already treats backend failure as FAULTED and stops all input.

## F12 conflict and explicit abort key

The real run showed F12 (the old default) fires the Steam screenshot shortcut. Production
LiveSolverMain therefore has no default: watch plus enable-input requires both target-exe
and abort-key. Abort-key without live enable-input is refused. Unknown names are refused.
Gameplay controls (UP, DOWN, LEFT, RIGHT, ENTER, RETURN, SELECT, TAB, PROCEED) can never be
abort keys. Supported keys are F1 to F24, PAUSE, SCROLL_LOCK, with documented
non-exhaustive conflicts: F12 Steam screenshot (plus Ctrl F12 Game Recording marker),
F11 Ctrl F11 Steam manual recording context, F1 F2 F3 Rockstar Editor group, F9 F10 GTA
drop weapon and ammo defaults. User bindings are authoritative. A selected key with a known
conflict prints a WARNING before watching starts. Banners print the actual selected key
(input delivery SCANCODE_BATCH, emergency abort SCROLL_LOCK); F12 is printed only when
explicitly selected. SCROLL_LOCK was manually validated (ABORTED, zero input) but is NOT
hardcoded: the intended smoke invocation passes it explicitly. Production uses
EmergencyAbortKey in the input package; DiagnosticAbortKey remains for the frozen probes
and production never imports input.diagnostic.

## Production smoke diagnostic (pending, document only)

InputDiagnosticMain still sends at most one UP DOWN LEFT RIGHT SELECT tap, still refuses
PROCEED, same foreground pin, same abort gates, no solver or recognition or repeat, but now
requires an explicit abort-key (F12 is no longer assumed) and drives the actual promoted
WindowsSendInputSink. Post-merge user runs UP then SELECT with target-exe GTA5_Enhanced.exe
plus abort-key SCROLL_LOCK plus countdown 5, expecting exactly one visible action each.
PROCEED needs no second smoke here: dedicated Tab was validated and production mapping is
byte and flag equivalent and pinned. A later independent Tab smoke, if wanted, must stay in
a harmless pause-map context, never in the vault. Do NOT run these during implementation.

## What did not change

- GuardedPlanExecutor: abort and focus checks, visual verification, four-selected before
  PROCEED, PROCEED once, never retries Tab, latches on mismatch: unchanged. Promotion sits
  beneath GameInputSink. No navigation changes.
- Recognition, matching, normalization, assignment, lifecycle, thresholds, layout, capture,
  Stage 8A and 8B code: unchanged.

## Stage 8D blocked

Stage 8D (controlled vault E2E) remains blocked until independent review merges this work
AND the user passes the harmless UP plus SELECT production-backend smoke. Only then is
Stage 8C complete and Stage 8D preparation allowed.
