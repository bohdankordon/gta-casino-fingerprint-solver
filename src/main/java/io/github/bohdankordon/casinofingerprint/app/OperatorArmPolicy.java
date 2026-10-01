package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.MonitorSelector;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, UI-independent operator arming policy for the Stage 9B desktop UI.
 *
 * <p>The Swing window is a thin view over this policy: every ARM eligibility rule is
 * decided here so it can be tested without creating windows, and the armed backend
 * options are built through ArmedSessionConfig with LiveSolverOptions validation.
 * Production support is exactly physical 2560x1440; nothing here changes that.
 */
public final class OperatorArmPolicy {
    /** Production profile width: only physical 2560x1440 is supported. */
    public static final int REQUIRED_WIDTH = 2560;
    /** Production profile height: only physical 2560x1440 is supported. */
    public static final int REQUIRED_HEIGHT = 1440;
    /** Validated default prefilled in the target executable field. */
    public static final String DEFAULT_TARGET_EXECUTABLE = "GTA5_Enhanced.exe";

    private OperatorArmPolicy() {
    }

    /** ARM eligibility verdict with actionable messages for the operator. */
    public record Eligibility(boolean allowed, List<String> issues) {
        /** Single-line explanation shown under the ARM button. */
        public String explain() {
            return String.join(" ", issues);
        }
    }

    /**
     * Checks whether ARM is currently allowed.
     *
     * @param selectedMonitor explicitly selected monitor, may be null
     * @param targetExecutable raw target executable field value, may be null or blank
     * @param abortKey explicitly selected emergency abort key, may be null
     * @param priorSessionTerminated true once any armed session ended in this process
     */
    public static Eligibility check(MonitorInfo selectedMonitor, String targetExecutable,
            EmergencyAbortKey abortKey, boolean priorSessionTerminated) {
        List<String> issues = new ArrayList<>();
        if (priorSessionTerminated) {
            issues.add("A live session already ended in this process."
                    + " Restart the application to arm a new live session.");
        }
        if (selectedMonitor == null
                || !selectedMonitor.supportsPhysicalResolution(REQUIRED_WIDTH, REQUIRED_HEIGHT)) {
            issues.add("Select a supported 2560x1440 display.");
        }
        if (!isTargetExecutableValid(targetExecutable)) {
            issues.add("Enter the target executable (for example GTA5_Enhanced.exe).");
        }
        if (abortKey == null) {
            issues.add("Select an emergency abort key.");
        }
        return new Eligibility(issues.isEmpty(), List.copyOf(issues));
    }

    /** A target executable is valid when it names the foreground executable. */
    public static boolean isTargetExecutableValid(String targetExecutable) {
        return targetExecutable != null && !targetExecutable.isBlank();
    }

    /**
     * Preselects a monitor only when exactly one attached monitor reports the supported
     * physical resolution. Zero or several matches require an explicit operator choice,
     * mirroring MonitorSelector automatic-selection honesty.
     */
    public static Optional<MonitorInfo> preselectCandidate(List<MonitorInfo> monitors) {
        Objects.requireNonNull(monitors, "monitors");
        List<MonitorInfo> matching = MonitorSelector.matchingPhysicalResolution(monitors,
                new Resolution(REQUIRED_WIDTH, REQUIRED_HEIGHT));
        if (matching.size() == 1) {
            return Optional.of(matching.get(0));
        }
        return Optional.empty();
    }

    /**
     * Immediate warning text for a selected abort key with a documented shortcut
     * conflict, or empty when the key documents none. Existing warnings are surfaced,
     * never suppressed.
     */
    public static Optional<String> abortConflictText(EmergencyAbortKey abortKey) {
        if (abortKey == null) {
            return Optional.empty();
        }
        return abortKey.knownConflict().map(
                note -> "Warning: " + abortKey.symbolicName() + " - " + note
                        + ". Check your own bindings before arming.");
    }
}
