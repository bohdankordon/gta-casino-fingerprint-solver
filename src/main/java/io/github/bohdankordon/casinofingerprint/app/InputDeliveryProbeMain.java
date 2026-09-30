package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcome;
import io.github.bohdankordon.casinofingerprint.input.diagnostic.DiagnosticDeliveryOutcomeSource;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsDiagnosticInputSink;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Stage 8C.2 command-line entry point: one guarded key tap that characterizes ONE explicitly
 * selected input-delivery mode against the real GTA installation, and nothing else.
 *
 * <pre>
 * InputDeliveryProbeMain --enable-input --target-exe &lt;exact.exe&gt;
 *     --control &lt;UP|DOWN|LEFT|RIGHT|SELECT&gt; --delivery-mode &lt;mode&gt;
 *     --abort-key &lt;F1..F24|PAUSE|SCROLL_LOCK&gt; [--hold-ms &lt;n&gt;] [--countdown-seconds &lt;n&gt;]
 * InputDeliveryProbeMain --help
 * </pre>
 *
 * <p>The probe reuses the unchanged Stage 8C.1 safety core {@link SingleTapInputDiagnostic} for
 * every gate (Windows, opt-in, Tab refusal, countdown, abort polls, exact-executable foreground
 * pin, pin re-check, final pre-tap abort check, exactly one tap, no retry) and swaps only the
 * delivery backend: one explicitly selected delivery mode of the characterization sink. Each
 * invocation sends AT MOST ONE logical tap; nothing is batched, repeated or retried, and the
 * tool performs no recognition, capture or navigation.
 *
 * <p>Exit codes: 0 tap sent (or help), 2 usage refusal, 3 runtime refusal or input failure. An
 * exit code of 3 alone does NOT mean that no input was sent: ordinary refusals happen before
 * the tap and guarantee zero input, while an INPUT ERROR follows the one allowed attempt and
 * does not confirm complete delivery. A SENT line never claims that GTA visibly reacted.
 */
public final class InputDeliveryProbeMain {
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_REFUSED = 3;

    private InputDeliveryProbeMain() {
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
        InputDeliveryProbeOptions options;
        try {
            options = InputDeliveryProbeOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("PROBE REFUSED: " + e.getMessage());
            System.err.print(InputDeliveryProbeOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(InputDeliveryProbeOptions.usage());
            return;
        }
        int exit = run(options, System.out, System.err, Win32Support::isWindows, Thread::sleep,
                () -> productionBackends(options));
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    /**
     * Runs one characterization invocation.
     *
     * @param windows OS gate; {@code Win32Support::isWindows} in production, a fake in tests
     * @param sleeper countdown sleeper; {@code Thread::sleep} in production, a fake in tests
     * @param backends backend factory; the Windows diagnostic backends in production, fakes in
     *        tests; never invoked on a non-Windows host
     * @return process exit code
     */
    static int run(InputDeliveryProbeOptions options, PrintStream out, PrintStream err,
            BooleanSupplier windows, InputDiagnosticSleeper sleeper, Supplier<Backends> backends) {
        if (!windows.getAsBoolean()) {
            err.println("PROBE REFUSED: " + InputDiagnosticStatus.NON_WINDOWS.label()
                    + ": gameplay input requires Windows (os.name is \""
                    + System.getProperty("os.name", "")
                    + "\"); no native input backend was constructed");
            return EXIT_REFUSED;
        }
        Backends natives = backends.get();
        printArmed(out, options);
        InputDiagnosticRequest request = new InputDiagnosticRequest(options.targetExecutable(),
                options.control(), true, options.countdownSeconds());
        InputDiagnosticResult result = new SingleTapInputDiagnostic(natives.sink(),
                natives.guard(), natives.abort(), sleeper, windows,
                options.abortKey().symbolicName()).run(request);
        Optional<DiagnosticDeliveryOutcome> recorded = outcomeOf(natives.sink());
        if (result.sent()) {
            out.println("PROBE SENT exactly one " + options.control() + " tap using delivery mode "
                    + options.deliveryMode() + " (" + holdPhrase(options) + ") to pinned "
                    + options.targetExecutable() + " target");
            out.println("PROBE delivery submission succeeded: Windows accepted the native"
                    + " submissions below; only the human operator can determine whether GTA"
                    + " visibly reacted");
            recorded.ifPresent(outcome -> printOutcome(out, outcome));
            if (recorded.filter(DiagnosticDeliveryOutcome::abortedDuringHold).isPresent()) {
                out.println("PROBE note: the emergency abort key ("
                        + options.abortKey().symbolicName() + ") fired during the hold: the key"
                        + " was released early and the key-up was still submitted; an abort after"
                        + " the key-down never suppresses the key-up");
            }
            out.println("PROBE no further input will be sent");
            out.println("PROBE probe complete");
            return EXIT_OK;
        }
        if (result.inputAttempted()) {
            // Never claim zero input here: the one tap was attempted and may have been partially
            // delivered, so the operator must treat the key as potentially delivered.
            err.println("PROBE " + result.status().label() + ": " + result.message());
            err.println("PROBE one tap was attempted but complete delivery was not confirmed;"
                    + " partial native input may have been delivered; no retry and no second tap"
                    + " will be attempted");
            recorded.ifPresent(outcome -> printOutcome(err, outcome));
            if (recorded.filter(outcome -> !outcome.keyUpConfirmed()).isPresent()) {
                err.println("PROBE warning: the key-up could not be confirmed; if the game now"
                        + " behaves as if that key is still held down, press and release the key"
                        + " once manually before any later attempt");
            }
            err.println("PROBE treat the attempted key as potentially delivered and check the"
                    + " current GTA state before any later manual attempt");
            return EXIT_REFUSED;
        }
        // Every remaining status is a pre-tap refusal, so zero input is guaranteed here.
        err.println("PROBE REFUSED: " + result.status().label() + ": " + result.message());
        err.println("PROBE no input was sent; this invocation is over and nothing is retried"
                + " automatically");
        return EXIT_REFUSED;
    }

    /** Builds the production Windows backends: the probe's only native input site. */
    static Backends productionBackends(InputDeliveryProbeOptions options) {
        AbortSignal abort = new WindowsEmergencyAbort(options.abortKey().virtualKeyCode());
        GameInputSink sink = new WindowsDiagnosticInputSink(options.deliveryMode(),
                options.holdMillis(), abort);
        return new Backends(sink, new WindowsForegroundTargetGuard(), abort);
    }

    private static void printArmed(PrintStream out, InputDeliveryProbeOptions options) {
        out.println("PROBE INPUT ARMED");
        out.println("PROBE requested control: " + options.control());
        out.println("PROBE delivery mode: " + options.deliveryMode() + " ("
                + options.deliveryMode().description() + ")");
        if (options.deliveryMode().holds()) {
            out.println("PROBE hold: " + options.holdMillis() + " ms between the key-down and"
                    + " key-up submissions");
        } else {
            out.println("PROBE hold: none; key-down and key-up are submitted in one batch");
        }
        out.println("PROBE target executable: " + options.targetExecutable());
        out.println("PROBE exactly ONE tap maximum");
        out.println("PROBE emergency abort key: " + options.abortKey().symbolicName());
        options.abortKey().knownConflict().ifPresent(note -> out.println("PROBE WARNING: "
                + options.abortKey().symbolicName() + " has a documented shortcut conflict ("
                + note + "); it was chosen explicitly, so check your own bindings"));
        out.println("PROBE switch to GTA now; countdown: " + options.countdownSeconds()
                + " s before the foreground gates");
    }

    private static String holdPhrase(InputDeliveryProbeOptions options) {
        return options.deliveryMode().holds() ? "hold " + options.holdMillis() + " ms"
                : "no hold, one batch";
    }

    private static void printOutcome(PrintStream stream, DiagnosticDeliveryOutcome outcome) {
        stream.println("PROBE delivery outcome: mode " + outcome.mode() + ", hold "
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
