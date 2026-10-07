package de.farmpulse.rpsim.cycle;

import java.util.Collection;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ResolvableType;

/**
 * Technical review 10/2026, Phase 1.3: Spring's own multicaster, registered under
 * {@code AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME}. It behaves exactly as before for every
 * {@code publishEvent}; it only exposes the ordered listener list so {@link CycleDispatcher} can run the listeners of
 * a bridge-cycle event one by one.
 */
public class ListenerAwareMulticaster extends SimpleApplicationEventMulticaster {

    /** The listeners Spring would call for this event, in Spring's order ({@code @Order}). */
    public Collection<ApplicationListener<?>> listenersFor(ApplicationEvent event) {
        return getApplicationListeners(event, ResolvableType.forInstance(event));
    }

    /** Calls one listener the way {@code multicastEvent} does (no error handler: exceptions reach the caller). */
    public void invoke(ApplicationListener<?> listener, ApplicationEvent event) {
        invokeListener(listener, event);
    }
}
