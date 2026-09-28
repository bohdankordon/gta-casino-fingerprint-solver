package io.github.bohdankordon.casinofingerprint.navigation;

import java.util.Objects;

/**
 * One abstract step of a dry-run solve plan: a domain intention, never an input event.
 *
 * <ul>
 *   <li>{@link Navigate} moves the selector one proven step (a future stage may send an
 *       arrow key);</li>
 *   <li>{@link Select} confirms the focused candidate (a future stage may send Enter);</li>
 *   <li>{@link Proceed} advances after all four selections (a future stage may send Tab).</li>
 * </ul>
 *
 * <p>The parenthesised hints describe where these intentions may eventually map. No mapping
 * exists here: there are no key codes, no key events and no input APIs anywhere in this
 * package, only the intention.
 */
public sealed interface DryRunAction permits DryRunAction.Navigate, DryRunAction.Select, DryRunAction.Proceed {
    /** Short abstract rendering such as RIGHT, SELECT C3 or PROCEED. */
    String render();

    /** Move the selector one proven step in the given direction. */
    record Navigate(Move move) implements DryRunAction {
        /** @param move navigation intention; required */
        public Navigate {
            Objects.requireNonNull(move, "move");
        }

        @Override
        public String render() {
            return move.name();
        }
    }

    /** Confirm the currently focused candidate. */
    record Select(GridPosition candidate) implements DryRunAction {
        /** @param candidate confirmed candidate; required */
        public Select {
            Objects.requireNonNull(candidate, "candidate");
        }

        @Override
        public String render() {
            return "SELECT " + candidate;
        }
    }

    /** Advance the round after all four selections. Always the last action. */
    record Proceed() implements DryRunAction {
        @Override
        public String render() {
            return "PROCEED";
        }
    }
}
