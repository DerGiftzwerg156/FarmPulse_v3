package de.farmpulse.rpsim.cycle;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;

@Configuration(proxyBeanMethods = false)
public class CycleConfig {

    /** Static and without dependencies: the context creates the multicaster before any other singleton. */
    @Bean(name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public static ListenerAwareMulticaster applicationEventMulticaster() {
        return new ListenerAwareMulticaster();
    }
}
