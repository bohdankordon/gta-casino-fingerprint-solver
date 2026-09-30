package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import java.util.Objects;

/**
 * One complete, pure delivery plan for a single characterization tap: the exact key-down and
 * key-up strokes, the mode that shapes the submission and the explicit hold. Nothing here
 * sleeps, submits or touches Windows; the plan is the testable description the delivery
 * executes.
 *
 * <p>BATCH modes submit both strokes in ONE native call and never sleep, so their plan carries
 * a hold of zero and {@link #batched()} is true. HOLD modes submit the key-down, hold for the
 * explicit duration and then submit the key-up, so exactly one stroke is in flight at a time.
 *
 * @param mode the delivery shape; required
 * @param down the key-down stroke; required, not a release
 * @param up the key-up stroke; required, a release
 * @param holdMillis the explicit hold between key-down and key-up, zero for the BATCH modes
 */
public record DiagnosticDeliveryPlan(DiagnosticInputDeliveryMode mode, DiagnosticKeyStroke down,
        DiagnosticKeyStroke up, long holdMillis) {
    /** Default diagnostic hold of the HOLD modes, used when {@code --hold-ms} is omitted. */
    public static final int DEFAULT_HOLD_MILLIS = 50;
    /** Lower bound of the explicit hold: below this the hold would hardly separate the strokes. */
    public static final int MIN_HOLD_MILLIS = 10;
    /** Upper bound of the explicit hold: a held arrow must never stay down noticeably long. */
    public static final int MAX_HOLD_MILLIS = 200;

    public DiagnosticDeliveryPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(down, "down");
        Objects.requireNonNull(up, "up");
        if (down.keyUp()) {
            throw new IllegalArgumentException("the down stroke must be a key-down");
        }
        if (!up.keyUp()) {
            throw new IllegalArgumentException("the up stroke must be a key-up");
        }
        validateHold(mode, holdMillis);
    }

    /** True when the tap is ONE native batch of both strokes with no hold and no sleep. */
    public boolean batched() {
        return !mode.holds();
    }

    /**
     * Builds the plan for one explicitly requested control.
     *
     * @throws IllegalArgumentException for PROCEED (Tab is never sent) and for a hold that does
     *         not obey the BATCH/HOLD contract
     */
    public static DiagnosticDeliveryPlan forTap(GameControl control,
            DiagnosticInputDeliveryMode mode, int holdMillis) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(mode, "mode");
        if (control == GameControl.PROCEED) {
            throw new IllegalArgumentException("PROCEED is forbidden in Stage 8C: Tab is never"
                    + " sent by the characterization probe");
        }
        validateHold(mode, holdMillis);
        int hold = mode.holds() ? holdMillis : 0;
        if (mode.usesScanCodes()) {
            ScanCodeSpec spec = ScanCodeSpec.forControl(control);
            return new DiagnosticDeliveryPlan(mode, DiagnosticKeyStroke.scanCode(spec, false),
                    DiagnosticKeyStroke.scanCode(spec, true), hold);
        }
        int virtualKey = virtualKey(control);
        return new DiagnosticDeliveryPlan(mode, DiagnosticKeyStroke.virtualKey(virtualKey, false),
                DiagnosticKeyStroke.virtualKey(virtualKey, true), hold);
    }

    /**
     * Validates the hold contract of one mode: the HOLD modes require an explicit hold inside
     * 10..200 ms, and the BATCH modes must carry zero because they never sleep.
     *
     * @throws IllegalArgumentException when the hold contradicts the mode
     */
    public static void validateHold(DiagnosticInputDeliveryMode mode, long holdMillis) {
        Objects.requireNonNull(mode, "mode");
        if (mode.holds()) {
            if (holdMillis < MIN_HOLD_MILLIS || holdMillis > MAX_HOLD_MILLIS) {
                throw new IllegalArgumentException("--hold-ms must be between " + MIN_HOLD_MILLIS
                        + " and " + MAX_HOLD_MILLIS + " ms for " + mode + ", got " + holdMillis);
            }
        } else if (holdMillis != 0) {
            throw new IllegalArgumentException(mode + " submits one no-sleep batch: a hold of "
                    + holdMillis + " ms is contradictory (drop --hold-ms)");
        }
    }

    /**
     * The virtual-key code of one allowed control. A test pins these five values equal to the
     * production virtual-key mapping, so the characterization cannot drift away from the
     * baseline while this package stays free of any native Windows reference.
     */
    private static int virtualKey(GameControl control) {
        return switch (control) {
            case UP -> 0x26;
            case DOWN -> 0x28;
            case LEFT -> 0x25;
            case RIGHT -> 0x27;
            case SELECT -> 0x0D;
            case PROCEED -> throw new IllegalArgumentException(
                    "PROCEED is forbidden in Stage 8C: Tab is never sent");
        };
    }
}
