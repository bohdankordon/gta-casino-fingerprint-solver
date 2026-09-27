package io.github.bohdankordon.casinofingerprint.gameplay;

/** Locates the representative gameplay fixture and records its provenance. */
public final class GameplayFixture {
    /** Repo-relative path of the representative gameplay screenshot (forward slashes). */
    public static final String SOURCE_REL =
            "fixtures/gameplay/source/representative-fingerprint-minigame-2560x1440.png";
    /** Repo-relative path of the representative 2560x1440 layout manifest (forward slashes). */
    public static final String LAYOUT_REL = "fixtures/gameplay/layout/representative-2560x1440.csv";
    /** Exact fixture dimensions; OpenCV must decode exactly this size. */
    public static final int EXPECTED_WIDTH = 2560;
    /** Exact fixture dimensions; OpenCV must decode exactly this size. */
    public static final int EXPECTED_HEIGHT = 1440;
    /** SHA-256 of the immutable fixture bytes, also recorded in {@code fixtures/gameplay/README.md}. */
    public static final String EXPECTED_SOURCE_SHA256 =
            "250B9E7FFFD9BF95D7358BFA47FE59D6EB4E49135E7731C0128BE32ED5CD2495";

    private GameplayFixture() {
    }
}
