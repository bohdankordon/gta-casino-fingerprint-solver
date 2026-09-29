package io.github.bohdankordon.casinofingerprint.navigation;

/**
 * Abstract selector navigation intentions on the 2x4 fingerprint grid.
 *
 * <p>These are DOMAIN intentions only: they name the direction the selector should travel,
 * not any key code, key press or input API. A future automation stage may map a move to an
 * arrow key, a select to Enter and a proceed to Tab, but no such mapping exists here and no
 * production class in this package may contain one.
 */
public enum Move {
    /** One row up: the same column, one row closer to the top. */
    UP,
    /** One row down: the same column, one row closer to the bottom. */
    DOWN,
    /** One column left: the same row, one column closer to the left. */
    LEFT,
    /** One column right: the same row, one column closer to the right. */
    RIGHT
}
