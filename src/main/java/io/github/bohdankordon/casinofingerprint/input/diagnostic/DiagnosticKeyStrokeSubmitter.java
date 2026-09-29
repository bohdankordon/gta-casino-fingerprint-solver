package io.github.bohdankordon.casinofingerprint.input.diagnostic;

import io.github.bohdankordon.casinofingerprint.input.GameInputException;

/**
 * The characterization delivery's only native submission seam: one call inserts the given
 * strokes into the Windows keyboard input stream, in order, and reports how many events
 * Windows accepted -- exactly the return value of the underlying batch keyboard submission.
 *
 * <p>Production is the JNA-backed user-mode Windows path of the Windows diagnostic sink; tests
 * use recording fakes, so no automated test can emit native input.
 */
@FunctionalInterface
public interface DiagnosticKeyStrokeSubmitter {
    /**
     * Submits the strokes as one native batch, in order.
     *
     * @return the number of events Windows reported as inserted
     * @throws GameInputException when the native call itself failed
     */
    int submit(DiagnosticKeyStroke... strokes);
}
