package io.github.bohdankordon.casinofingerprint.capture;

/**
 * Creates a capture bound to one monitor and one required physical resolution.
 *
 * <p>This is the seam between monitor selection and the capture backend: runtimes resolve a
 * monitor, then ask the factory for a capture of that monitor. Tests replace the AWT backend
 * with an in-memory sequence so end-to-end orchestration stays deterministic and display-free.
 */
@FunctionalInterface
public interface CaptureFactory {
    /**
     * @param monitor selected monitor
     * @param required physical resolution the capture must deliver exactly
     * @return a capture that fails rather than scaling when it cannot deliver {@code required}
     */
    ScreenCapture create(MonitorInfo monitor, Resolution required);
}
