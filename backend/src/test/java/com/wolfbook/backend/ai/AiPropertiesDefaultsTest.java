package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiPropertiesDefaultsTest {

    @Test
    void chatModelShouldDefaultToMiniMaxM27() {
        AiProperties properties = new AiProperties();

        assertEquals("MiniMax-M2.7", properties.getChat().getModel());
    }
}
