package io.github.bohdankordon.casinofingerprint.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Chooses the capture variant whose pixels are EXACTLY the required physical resolution.
 *
 * <p>Selection is deliberately exact and never approximate: no upscaling, no downscaling, no
 * nearest-size fallback. The Stage 2 ROI coordinates are measured in physical pixels of the
 * gameplay layout, so any other size would silently move every region. When no variant matches,
 * the selector raises {@link UnsupportedResolutionException} with the full HiDPI diagnostic.
 *
 * <p>Selection is deterministic: the first variant in capture order that matches wins.
 */
public final class ResolutionVariantSelector {
    private ResolutionVariantSelector() {
    }

    /**
     * @param variants resolution variants returned by the backend, in capture order
     * @param monitor monitor the capture was taken from (diagnostic context only)
     * @param required physical resolution the gameplay layout needs
     * @return the first variant whose pixel size is exactly {@code required}
     * @throws UnsupportedResolutionException when no variant matches
     */
    public static ResolutionVariant selectExact(
            List<ResolutionVariant> variants, MonitorInfo monitor, Resolution required) {
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(required, "required");
        List<ResolutionVariant> candidates = List.copyOf(variants);
        for (ResolutionVariant variant : candidates) {
            if (variant.resolution().matches(required.width(), required.height())) {
                return variant;
            }
        }
        List<Resolution> observed = new ArrayList<>(candidates.size());
        for (ResolutionVariant variant : candidates) {
            observed.add(variant.resolution());
        }
        throw new UnsupportedResolutionException(monitor, required, observed);
    }
}
