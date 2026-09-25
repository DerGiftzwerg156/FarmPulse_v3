package de.farmpulse.rpsim.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonBeans {

    @Bean
    @ConditionalOnMissingBean
    public RandomSource randomSource() {
        return new RandomSource();
    }
}
