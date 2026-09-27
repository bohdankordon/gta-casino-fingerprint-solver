package io.github.bohdankordon.casinofingerprint.dataset;

/** Canonical dataset asset kinds. */
public enum ReferenceAssetType {
    TARGET,
    FRAGMENT;

    /** Parses the manifest {@code asset_type} column (case-insensitive). */
    public static ReferenceAssetType fromManifest(String value) {
        if (value == null) {
            throw new IllegalArgumentException("asset_type must not be null");
        }
        switch (value.trim().toLowerCase()) {
            case "target":
                return TARGET;
            case "fragment":
                return FRAGMENT;
            default:
                throw new IllegalArgumentException("Unknown asset_type: " + value);
        }
    }
}
