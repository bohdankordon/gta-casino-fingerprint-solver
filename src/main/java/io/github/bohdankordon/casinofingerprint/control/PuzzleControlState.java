package io.github.bohdankordon.casinofingerprint.control;

import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One production reading of the raw gameplay control UI.
 */
public final class PuzzleControlState {
    private final GridPosition focus;
    private final SortedSet<Integer> selected;
    private final boolean valid;
    private final String detail;
    private final int[] bracketScores;
    private final int[] innerMeans;

    private PuzzleControlState(GridPosition focus, SortedSet<Integer> selected, boolean valid,
            String detail, int[] bracketScores, int[] innerMeans) {
        this.focus = focus;
        this.selected = Collections.unmodifiableSortedSet(new TreeSet<>(selected));
        this.valid = valid;
        this.detail = detail;
        this.bracketScores = bracketScores.clone();
        this.innerMeans = innerMeans.clone();
    }

    /** Builds an actionable reading. */
    public static PuzzleControlState valid(GridPosition focus, SortedSet<Integer> selected,
            String detail, int[] bracketScores, int[] innerMeans) {
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(detail, "detail");
        requireScores(bracketScores, innerMeans);
        for (int candidate : selected) {
            if (candidate < 0 || candidate > 7) {
                throw new IllegalArgumentException("Selected candidate must be 0..7, got " + candidate);
            }
        }
        return new PuzzleControlState(focus, new TreeSet<>(selected), true, detail, bracketScores, innerMeans);
    }

    /** Builds a fail-closed refusal: no focus, no selected set, never actionable. */
    public static PuzzleControlState invalid(String detail, int[] bracketScores, int[] innerMeans) {
        Objects.requireNonNull(detail, "detail");
        requireScores(bracketScores, innerMeans);
        return new PuzzleControlState(null, new TreeSet<>(), false, detail, bracketScores, innerMeans);
    }

    private static void requireScores(int[] bracketScores, int[] innerMeans) {
        Objects.requireNonNull(bracketScores, "bracketScores");
        Objects.requireNonNull(innerMeans, "innerMeans");
        if (bracketScores.length != 8 || innerMeans.length != 8) {
            throw new IllegalArgumentException("Control scores need eight entries each");
        }
    }

    /** Focused candidate; empty unless valid. */
    public Optional<GridPosition> focus() {
        return Optional.ofNullable(focus);
    }

    /** Selected candidate indices in ascending order; empty unless valid. */
    public SortedSet<Integer> selected() {
        return selected;
    }

    /** True only when the reading is unambiguous and within calibrated bounds. */
    public boolean valid() {
        return valid;
    }

    /** Short evidence note or the refusal reason. */
    public String detail() {
        return detail;
    }

    /** Per-tile focus-bracket hot-pixel counts, row-major 0..7. */
    public int[] bracketScores() {
        return bracketScores.clone();
    }

    /** Per-tile interior means, row-major 0..7. */
    public int[] innerMeans() {
        return innerMeans.clone();
    }

    @Override
    public String toString() {
        if (!valid) {
            return "CONTROL_INVALID(" + detail + ") scores=" + Arrays.toString(bracketScores);
        }
        return "CONTROL focus=" + focus + " selected=" + selected + " " + detail;
    }
}
