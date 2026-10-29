package com.wolfbook.backend.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeSupportLevelsTest {

    @Test
    void normalizeFallsBackToManualOnlyForBlankAndUnknownValues() {
        assertEquals(JudgeSupportLevels.MANUAL_ONLY, JudgeSupportLevels.normalize(null));
        assertEquals(JudgeSupportLevels.MANUAL_ONLY, JudgeSupportLevels.normalize("   "));
        assertEquals(JudgeSupportLevels.MANUAL_ONLY, JudgeSupportLevels.normalize("observer"));
    }

    @Test
    void normalizeKeepsSupportedLevelsLowerCased() {
        assertEquals(JudgeSupportLevels.FULL, JudgeSupportLevels.normalize("FULL"));
        assertEquals(JudgeSupportLevels.PARTIAL, JudgeSupportLevels.normalize(" partial "));
    }

    @Test
    void resolvePrefersRequestedValueAndFallsBackToCurrent() {
        assertEquals(JudgeSupportLevels.FULL, JudgeSupportLevels.resolve("FULL", JudgeSupportLevels.MANUAL_ONLY));
        assertEquals(JudgeSupportLevels.PARTIAL, JudgeSupportLevels.resolve(" ", "PARTIAL"));
    }

    @Test
    void manualOnlyCheckUsesNormalizedValue() {
        assertTrue(JudgeSupportLevels.isManualOnly("unknown"));
        assertTrue(JudgeSupportLevels.isManualOnly(" manual_only "));
    }
}
