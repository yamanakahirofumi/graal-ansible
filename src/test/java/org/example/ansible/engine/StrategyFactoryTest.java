package org.example.ansible.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyFactoryTest {

    @Test
    void testGetStrategyFree() {
        Strategy strategy = StrategyFactory.getStrategy("free");
        assertNotNull(strategy);
        assertTrue(strategy instanceof FreeStrategy);
        assertEquals("free", strategy.getName());
    }

    @Test
    void testGetStrategyFreeCaseInsensitive() {
        Strategy strategy = StrategyFactory.getStrategy("FREE");
        assertNotNull(strategy);
        assertTrue(strategy instanceof FreeStrategy);
        assertEquals("free", strategy.getName());
    }

    @Test
    void testGetStrategyLinear() {
        Strategy strategy = StrategyFactory.getStrategy("linear");
        assertNotNull(strategy);
        assertTrue(strategy instanceof LinearStrategy);
        assertEquals("linear", strategy.getName());
    }

    @Test
    void testGetStrategyLinearCaseInsensitive() {
        Strategy strategy = StrategyFactory.getStrategy("LINEAR");
        assertNotNull(strategy);
        assertTrue(strategy instanceof LinearStrategy);
        assertEquals("linear", strategy.getName());
    }

    @Test
    void testGetStrategyUnknownFallbackToLinear() {
        Strategy strategy = StrategyFactory.getStrategy("custom_unknown_strategy");
        assertNotNull(strategy);
        assertTrue(strategy instanceof LinearStrategy);
        assertEquals("linear", strategy.getName());
    }

    @Test
    void testGetStrategyNullFallbackToLinear() {
        Strategy strategy = StrategyFactory.getStrategy(null);
        assertNotNull(strategy);
        assertTrue(strategy instanceof LinearStrategy);
        assertEquals("linear", strategy.getName());
    }
}
