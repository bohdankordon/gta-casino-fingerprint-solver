package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Stage 9B UI stack is Swing plus exactly core FlatLaf 3.7.2: no companion
 * theme pack, no JavaFX, no browser UI. Loading the class needs no display, so
 * this pins the dependency on every CI platform.
 */
class FlatLafVersionTest {
    @Test
    void coreFlatLafIsPinned() throws Exception {
        Class<?> lookAndFeel = Class.forName("com.formdev.flatlaf.FlatDarkLaf");
        assertEquals("3.7.2",
                lookAndFeel.getPackage().getImplementationVersion());
    }
}
