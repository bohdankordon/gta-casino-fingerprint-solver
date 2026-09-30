package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcome;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcomeSource;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticInputDeliveryMode;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsProceedInputSink;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Stage 8C.3 dedicated command-line entry point: one guarded PROCEED (Tab) tap using the
 * fixed SCANCODE_BATCH delivery, and nothing else.
 *
 * <pre>
 * ProceedInputProbeMain --enable-input --enable-proceed-test
 *     --target-exe &lt;exact.exe&gt; --abort-key &lt;F1..F24|PAUSE|SCROLL_LOCK&gt;
 *     [--countdown-seconds &lt;n&gt;]
 * ProceedInputProbeMain --help
 * </pre>
 *
 * <p>The probe reuses the Stage 8C.1 safety ordering through the dedicated
 * {@link ProceedTapDiagnostic} core (Windows, double opt-in, PROCEED-only, countdown, abort
 * polls, exact-executable foreground pin, pin re-check, final pre-tap abort check, exactly
 * one Tab tap, no retry) and the validated scan-code submission seam. Each invocation sends
 * AT MOST ONE logical Tab tap; nothing is batched beyond the single down/up pair, repeated
 * or retried, and the tool performs no recognition, capture or navigation. The normal
 * {@code InputDeliveryProbeMain} keeps refusing PROCEED; this dedicated entry point is the
 * only Stage 8C diagnostic allowed to emit Tab.
 *
 * <p>Exit codes: 0 tap sent (or help), 2 usage refusal, 3 runtime refusal or input failure.
 * An exit code of 3 alone does NOT mean that no input was sent: ordinary refusals happen
 * before the tap and guarantee zero input, while an INPUT ERROR follows the one allowed
 * attempt and does not confirm complete delivery. A SENT line never claims that GTA visibly
 * reacted.
 */
public final class ProceedInputProbeMain {
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_REFUSED = 3;

    private ProceedInputProbeMain() {
    }

    /** The three native input collaborators of one probe run, built only after the OS gate. */
    record Backends(GameInputSink sink, ForegroundTargetGuard guard, AbortSignal abort) {
        Backends {
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(abort, "abort");
        }
    }

    public static void main(String[] args) {
        ProceedInputProbeOptions options;
        try {
            options = ProceedInputProbeOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("PROCEED PROBE REFUSED: " + e.getMessage());
            System.err.print(ProceedInputProbeOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(ProceedInputProbeOptions.usage());
            return;
        }
        int exit = run(options, System.out, System.err, Win32Support::isWindows, Thread::sleep,
                () -> productionBackends(options));
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    /**
     * Runs one dedicated proceed invocation.
     *
     * @param windows OS gate; {@code Win32Support::isWindows} in production, a fake in tests
     * @param sleeper countdown sleeper; {@code Thread::sleep} in production, a fake in tests
     * @param backends backend factory; the Windows proceed backends in production, fakes in
     *        tests; never invoked on a non-Windows host
     * @return process exit code
     */
    static int run(ProceedInputProbeOptions options, PrintStream out, PrintStream err,
            BooleanSupplier windows, InputDiagnosticSleeper sleeper, Supplier<Backends> backends) {
        if (!windows.getAsBoolean()) {
            err.println("PROCEED PROBE REFUSED: " + InputDiagnosticStatus.NON_WINDOWS.label()
                    + ": gameplay input requires Windows (os.name is \""
                    + System.getProperty("os.name", "") + "\"); no native input backend was"
                    + " constructed");
            return EXIT_REFUSED;
        }
        Backends natives = backends.get();
        printArmed(out, options);
        InputDiagnosticRequest request = new InputDiagnosticRequest(options.targetExecutable(),
                GameControl.PROCEED, true, options.countdownSeconds());
        InputDiagnosticResult result = new ProceedTapDiagnostic(natives.sink(),
                natives.guard(), natives.abort(), sleeper, windows,
                options.abortKey().symbolicName()).run(request);
        Optional<DiagnosticDeliveryOutcome> recorded = outcomeOf(natives.sink());
        if (result.sent()) {
            out.println("PROCEED PROBE SENT exactly one PROCEED tap using delivery mode "
                    + DiagnosticInputDeliveryMode.SCANCODE_BATCH + " (no hold, one batch)"
                    + " to pinned " + options.targetExecutable() + " target");
            out.println("PROCEED PROBE delivery submission succeeded: Windows accepted the"
                    + " native submissions below; only the human operator can determine whether"
                    + " GTA visibly reacted");
            recorded.ifPresent(outcome -> printOutcome(out, outcome));
            out.println("PROCEED PROBE no further input will be sent");
            out.println("PROCEED PROBE probe complete");
            return EXIT_OK;
        }
        if (result.inputAttempted()) {
            // Never claim zero input here: the one Tab was attempted and may have been
            // partially delivered, so the operator must treat Tab as potentially delivered.
            err.println("PROCEED PROBE " + result.status().label() + ": " + result.message());
            err.println("PROCEED PROBE one Tab tap was attempted but complete delivery was not"
                    + " confirmed; partial native input may have been delivered; no retry and"
                    + " no second tap will be attempted");
            recorded.ifPresent(outcome -> printOutcome(err, outcome));
            if (recorded.filter(outcome -> !outcome.keyUpConfirmed()).isPresent()) {
                err.println("PROCEED PROBE warning: the Tab key-up could not be confirmed; if"
                        + " the game now behaves as if Tab is still held down, press and release"
                        + " Tab once manually before any later attempt");
            }
            err.println("PROCEED PROBE treat the attempted Tab as potentially delivered and"
                    + " check the current GTA state before any later manual attempt");
            return EXIT_REFUSED;
        }
        // Every remaining status is a pre-tap refusal, so zero input is guaranteed here.
        err.println("PROCEED PROBE REFUSED: " + result.status().label() + ": " + result.message());
        err.println("PROCEED PROBE no input was sent; this invocation is over and nothing is"
                + " retried automatically");
        return EXIT_REFUSED;
    }

    /** Builds the production Windows backends: the proceed probe only native input site. */
    static Backends productionBackends(ProceedInputProbeOptions options) {
        AbortSignal abort = new WindowsEmergencyAbort(options.abortKey().virtualKeyCode());
        GameInputSink sink = new WindowsProceedInputSink();
        return new Backends(sink, new WindowsForegroundTargetGuard(), abort);
    }

    private static void printArmed(PrintStream out, ProceedInputProbeOptions options) {
        out.println("PROCEED PROBE INPUT ARMED");
        out.println("PROCEED PROBE requested control: PROCEED (Tab)");
        out.println("PROCEED PROBE delivery mode: " + DiagnosticInputDeliveryMode.SCANCODE_BATCH
                + " (" + DiagnosticInputDeliveryMode.SCANCODE_BATCH.description() + ")");
        out.println("PROCEED PROBE hold: none; Tab key-down and key-up are submitted in one"
                + " batch");
        out.println("PROCEED PROBE Tab scan code: Set-1 0x0F, non-extended, wVk zero,"
                + " KEYEVENTF_SCANCODE, no KEYEVENTF_EXTENDEDKEY, no VK_TAB");
        out.println("PROCEED PROBE target executable: " + options.targetExecutable());
        out.println("PROCEED PROBE exactly ONE Tab tap maximum");
        out.println("PROCEED PROBE emergency abort key: " + options.abortKey().symbolicName());
        options.abortKey().knownConflict().ifPresent(note -> out.println("PROCEED PROBE WARNING: "
                + options.abortKey().symbolicName() + " has a documented shortcut conflict ("
                + note + "); it was chosen explicitly, so check your own bindings"));
        out.println("PROCEED PROBE switch to GTA now; countdown: " + options.countdownSeconds()
                + " s before the foreground gates");
    }

    private static void printOutcome(PrintStream stream, DiagnosticDeliveryOutcome outcome) {
        stream.println("PROCEED PROBE delivery outcome: mode " + outcome.mode() + ", hold "
                + outcome.requestedHoldMillis() + " ms requested / " + outcome.actualHoldMillis()
                + " ms actual, key-down confirmed: " + yesNo(outcome.keyDownConfirmed())
                + ", key-up confirmed: " + yesNo(outcome.keyUpConfirmed())
                + ", key-up cleanup attempted: " + yesNo(outcome.keyUpCleanupAttempted())
                + ", abort during hold: " + yesNo(outcome.abortedDuringHold()));
    }

    private static Optional<DiagnosticDeliveryOutcome> outcomeOf(GameInputSink sink) {
        return sink instanceof DiagnosticDeliveryOutcomeSource source ? source.lastOutcome()
                : Optional.empty();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
