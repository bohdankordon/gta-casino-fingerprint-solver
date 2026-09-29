package io.github.bohdankordon.casinofingerprint.input;

/**
 * One complete gameplay key tap (key down plus key up) behind a tiny testable interface.
 *
 * <p>The orchestration talks only to this interface; production uses the Windows native key
 * backend while every test uses a recording fake, so no CI test can ever emit OS keyboard
 * input. Implementations must never expose raw key-down state: one {@link #tap} call is one
 * finished tap, and a backend that submitted a key-down must guarantee the key-up cleanup
 * before reporting an error.
 */
public interface GameInputSink {
    /**
     * Sends one complete key tap for the given gameplay intention.
     *
     * @param control gameplay intention; required
     * @throws GameInputException when the tap cannot be delivered as one complete tap
     */
    void tap(GameControl control);
}
