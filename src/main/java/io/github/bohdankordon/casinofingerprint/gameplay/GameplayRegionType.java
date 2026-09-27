package io.github.bohdankordon.casinofingerprint.gameplay;

/** Kinds of regions in a gameplay layout manifest. */
public enum GameplayRegionType {
    TARGET,
    CANDIDATE,
    PANEL;

    /** Parses the manifest {@code region_type} column (case-insensitive). */
    public static GameplayRegionType fromManifest(String value) {
        if (value == null) {
            throw new IllegalArgumentException("region_type must not be null");
        }
        switch (value.trim().toUpperCase()) {
            case "TARGET":
                return TARGET;
            case "CANDIDATE":
                return CANDIDATE;
            case "PANEL":
                return PANEL;
            default:
                throw new IllegalArgumentException("Unknown region_type: " + value);
        }
    }
}
