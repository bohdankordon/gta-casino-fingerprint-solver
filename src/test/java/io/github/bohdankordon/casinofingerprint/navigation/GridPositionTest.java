package io.github.bohdankordon.casinofingerprint.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic Stage 7A checks: all eight grid positions map to the documented row-major layout. */
class GridPositionTest {
    @Test
    void allEightPositionsMapToRowMajorRowsAndColumns() {
        int[][] expectedRowsAndColumns = {
                {0, 0}, {0, 1}, {1, 0}, {1, 1}, {2, 0}, {2, 1}, {3, 0}, {3, 1}
        };
        for (int index = 0; index < 8; index++) {
            GridPosition position = GridPosition.of(index);
            assertEquals(index, position.index(), "index " + index);
            assertEquals(expectedRowsAndColumns[index][0], position.row(), "row " + index);
            assertEquals(expectedRowsAndColumns[index][1], position.column(), "column " + index);
            assertEquals("C" + index, position.toString(), "code " + index);
        }
    }

    @Test
    void positionsAreCanonicalInstances() {
        List<GridPosition> constants =
                List.of(GridPosition.C0, GridPosition.C1, GridPosition.C2, GridPosition.C3,
                        GridPosition.C4, GridPosition.C5, GridPosition.C6, GridPosition.C7);
        List<GridPosition> lookedUp = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            lookedUp.add(GridPosition.of(index));
        }
        assertEquals(constants, lookedUp, "of() returns the canonical positions");
    }

    @Test
    void outOfRangeIndicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GridPosition.of(-1));
        assertThrows(IllegalArgumentException.class, () -> GridPosition.of(8));
    }
}
