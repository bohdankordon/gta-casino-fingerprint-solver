package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Stage 8C.1 command-line entry point: one guarded gameplay key tap for manual validation of
 * the Stage 7B native input layer, and nothing else.
 *
 * <pre>
 * InputDiagnosticMain --enable-input --target-exe &lt;exact.exe&gt;
 *     --control &lt;UP|DOWN|LEFT|RIGHT|SELECT&gt; [--countdown-seconds &lt;n&gt;]
 * InputDiagnosticMain --help
 * </pre>
 *
 * <p>This tool is deliberately tiny and is NOT the solver: no recognition, no capture, no
 * lifecycle, no navigation plan and no automatic repetition. One invocation emits AT MOST ONE
 * complete tap, only after the explicit opt-in, the countdown, the F12 abort polls, the exact
 * executable foreground pin and the final pre-tap gates. On a non-Windows OS it refuses before
 * any native input backend is constructed. Exit codes: 0 tap sent (or help), 2 usage refusal,
 * 3 runtime refusal or input failure. An exit code of 3 does NOT by itself mean that no input
 * was sent: ordinary refusals happen before the tap and guarantee zero input, while an
 * INPUT ERROR follows the one allowed tap attempt and does not confirm complete delivery.
 */
public final class InputDiagnosticMain {
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_REFUSED = 3;

    private InputDiagnosticMain() {
    }

    /** The three native input collaborators of one run, built only after the OS gate. */
    record Backends(GameInputSink sink, ForegroundTargetGuard guard, AbortSignal abort) {
        Backends {
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(abort, "abort");
        }
    }

    public static void main(String[] args) {
        InputDiagnosticOptions options;
        try {
            options = InputDiagnosticOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("DIAGNOSTIC REFUSED: " + e.getMessage());
            System.err.print(InputDiagnosticOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(InputDiagnosticOptions.usage());
            return;
        }
        int exit = run(options, System.out, System.err, Win32Support::isWindows, Thread::sleep,
                InputDiagnosticMain::productionBackends);
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    /**
     * Runs one invocation.
     *
     * @param windows OS gate; {@code Win32Support::isWindows} in production, a fake in tests
     * @param sleeper countdown sleeper; {@code Thread::sleep} in production, a fake in tests
     * @param backends backend factory; the Windows backends in production, fakes in tests;
     *        never invoked on a non-Windows host
     * @return process exit code
     */
    static int run(InputDiagnosticOptions options, PrintStream out, PrintStream err,
            BooleanSupplier windows, InputDiagnosticSleeper sleeper, Supplier<Backends> backends) {
        if (!windows.getAsBoolean()) {
            err.println("DIAGNOSTIC REFUSED: " + InputDiagnosticStatus.NON_WINDOWS.label()
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
                natives.guard(), natives.abort(), sleeper, windows).run(request);
        if (result.sent()) {
            out.println("DIAGNOSTIC SENT exactly one " + options.control() + " tap to pinned "
                    + options.targetExecutable() + " target");
            out.println("DIAGNOSTIC no further input will be sent");
            out.println("DIAGNOSTIC diagnostic complete");
            return EXIT_OK;
        }
        if (result.inputAttempted()) {
            // Never claim zero input here: the one tap was attempted and may have been partially
            // delivered, so the operator must treat the key as potentially delivered.
            err.println("DIAGNOSTIC " + result.status().label() + ": " + result.message());
            err.println("DIAGNOSTIC one tap was attempted but complete delivery was not"
                    + " confirmed; partial native input may have been delivered;"
                    + " no retry and no second tap will be attempted");
            err.println("DIAGNOSTIC treat the attempted key as potentially delivered and check"
                    + " the current GTA state before any later manual attempt");
            return EXIT_REFUSED;
        }
        // Every remaining status is a pre-tap refusal, so zero input is guaranteed here.
        err.println("DIAGNOSTIC REFUSED: " + result.status().label() + ": " + result.message());
        err.println("DIAGNOSTIC no input was sent; this invocation is over"
                + " and nothing is retried automatically");
        return EXIT_REFUSED;
    }

    /** Builds the production Windows backends: the diagnostic's only native input site. */
    static Backends productionBackends() {
        return new Backends(new WindowsSendInputSink(), new WindowsForegroundTargetGuard(),
                new WindowsEmergencyAbort());
    }

    private static void printArmed(PrintStream out, InputDiagnosticOptions options) {
        out.println("DIAGNOSTIC INPUT ARMED");
        out.println("DIAGNOSTIC requested control: " + options.control());
        out.println("DIAGNOSTIC target executable: " + options.targetExecutable());
        out.println("DIAGNOSTIC exactly ONE tap maximum");
        out.println("DIAGNOSTIC F12 aborts");
        out.println("DIAGNOSTIC switch to GTA now; countdown: " + options.countdownSeconds()
                + " s before the foreground gates");
    }
}
