package io.github.bohdankordon.casinofingerprint.navigation;

import java.util.Objects;

/**
 * One candidate cell of the Diamond Casino fingerprint grid.
 *
 * <p>Row-major layout, exactly as the recognition contract numbers candidates:
 *
 * <pre>
 * 0 1
 * 2 3
 * 4 5
 * 6 7
 * </pre>
 *
 * <p>A position is pure grid geometry: it knows its row and column but no navigation
 * topology. Which moves are legal from here is answered by a
 * {@link GridNavigationPolicy}, never by this value.
 */
public final class GridPosition implements Comparable<GridPosition> {
    /** Top-left candidate. */
    public static final GridPosition C0 = new GridPosition(0);
    /** Top-right candidate. */
    public static final GridPosition C1 = new GridPosition(1);
    /** Second row, left candidate. */
    public static final GridPosition C2 = new GridPosition(2);
    /** Second row, right candidate. */
    public static final GridPosition C3 = new GridPosition(3);
    /** Third row, left candidate. */
    public static final GridPosition C4 = new GridPosition(4);
    /** Third row, right candidate. */
    public static final GridPosition C5 = new GridPosition(5);
    /** Bottom-left candidate. */
    public static final GridPosition C6 = new GridPosition(6);
    /** Bottom-right candidate. */
    public static final GridPosition C7 = new GridPosition(7);

    private static final GridPosition[] ALL = {C0, C1, C2, C3, C4, C5, C6, C7};

    private final int index;

    private GridPosition(int index) {
        this.index = index;
    }

    /**
     * Returns the position of the given candidate index.
     *
     * @param index candidate index 0..7
     * @throws IllegalArgumentException when the index is outside 0..7
     */
    public static GridPosition of(int index) {
        if (index < 0 || index > 7) {
            throw new IllegalArgumentException("Candidate index must be 0..7, got " + index);
        }
        return ALL[index];
    }

    /** Candidate index 0..7. */
    public int index() {
        return index;
    }

    /** Grid row 0..3. */
    public int row() {
        return index / 2;
    }

    /** Grid column 0..1. */
    public int column() {
        return index % 2;
    }

    @Override
    public int compareTo(GridPosition other) {
        Objects.requireNonNull(other, "other");
        return Integer.compare(index, other.index);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GridPosition that && index == that.index;
    }

    @Override
    public int hashCode() {
        return index;
    }

    /** Short code such as C3. */
    @Override
    public String toString() {
        return "C" + index;
    }
}
