package de.farmpulse.rpsim.support;

import de.farmpulse.rpsim.common.RandomSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Deterministic randomness for statistical tests. */
@TestConfiguration
public class SeededRandomConfig {

    @Bean
    @Primary
    public RandomSource seededRandom() {
        return new RandomSource(20260925L);
    }
}
