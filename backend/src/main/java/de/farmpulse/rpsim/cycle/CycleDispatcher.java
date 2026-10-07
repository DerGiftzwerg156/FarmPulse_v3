package de.farmpulse.rpsim.cycle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CycleStep;
import de.farmpulse.rpsim.domain.CycleStepStatus;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.NoticeService;
import de.farmpulse.rpsim.repository.CycleStepRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Technical review 10/2026, Phase 1.3 (R-1, A-1): delivers an event of the bridge cycle to its listeners one by one,
 * each in its own transaction, in Spring's listener order.
 * <ul>
 *   <li>a listener that ran is journaled as {@code DONE} in the same transaction as its own changes, so a retried
 *   event never runs it twice;</li>
 *   <li>a failing listener stops the delivery ({@link Outcome#BLOCKED}): the rest of the queue waits and the next
 *   cycle retries from exactly this listener - the order of the game logic stays the same as before;</li>
 *   <li>after {@code rpsim.bridge.step-max-attempts} failures the listener is skipped for this event and the player
 *   gets a notice ({@link NoticeKind#CYCLE_STEP_SKIPPED}).</li>
 * </ul>
 * Runs outside a transaction only: the listeners must see what the previous listener committed.
 */
@Component
public class CycleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CycleDispatcher.class);

    /** Result of a delivery. */
    public enum Outcome {
        /** Every listener is done or skipped. */
        COMPLETE,
        /** A listener failed and will be retried by the next cycle. */
        BLOCKED
    }

    private final ListenerAwareMulticaster multicaster;
    private final ApplicationContext context;
    private final CycleStepRepository steps;
    private final SavegameRepository savegames;
    private final NoticeService notices;
    private final RpsimProperties props;
    private final TransactionTemplate tx;

    public CycleDispatcher(ListenerAwareMulticaster multicaster, ApplicationContext context, CycleStepRepository steps,
                           SavegameRepository savegames, NoticeService notices, RpsimProperties props,
                           PlatformTransactionManager transactions) {
        this.multicaster = multicaster;
        this.context = context;
        this.steps = steps;
        this.savegames = savegames;
        this.notices = notices;
        this.props = props;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Delivers {@code payload} as sub step {@code stepKey} of the queued event {@code cycleEventId}.
     */
    public Outcome dispatch(Long savegameId, Long cycleEventId, String stepKey, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cycle events are delivered outside a transaction");
        }
        ApplicationEvent event = payload instanceof ApplicationEvent e ? e : new PayloadApplicationEvent<>(context, payload);
        for (ApplicationListener<?> listener : multicaster.listenersFor(event)) {
            String id = listenerId(listener);
            Optional<CycleStep> before = steps.findByCycleEventIdAndStepKeyAndListenerId(cycleEventId, stepKey, id);
            if (before.isPresent() && before.get().getStatus() != CycleStepStatus.FAILED) {
                continue; // DONE or SKIPPED
            }
            try {
                tx.executeWithoutResult(s -> {
                    multicaster.invoke(listener, event);
                    record(savegameId, cycleEventId, stepKey, id, CycleStepStatus.DONE, null);
                });
            } catch (RuntimeException e) {
                int attempts = before.map(CycleStep::getAttempts).orElse(0) + 1;
                boolean skip = attempts >= Math.max(1, props.getBridge().getStepMaxAttempts());
                log.warn("Cycle step {} of {} failed (attempt {}{}): {}", id, payload.getClass().getSimpleName(), attempts,
                        skip ? ", skipped" : ", retried next cycle", e.toString(), e);
                tx.executeWithoutResult(s -> {
                    record(savegameId, cycleEventId, stepKey, id, skip ? CycleStepStatus.SKIPPED : CycleStepStatus.FAILED,
                            e.toString());
                    if (skip) {
                        notifySkipped(savegameId, id, payload, e);
                    }
                });
                if (!skip) {
                    return Outcome.BLOCKED;
                }
            }
        }
        return Outcome.COMPLETE;
    }

    /** {@code de.Class.method(Param)} for {@code @EventListener} methods, the class name otherwise. */
    static String listenerId(ApplicationListener<?> listener) {
        if (listener instanceof SmartApplicationListener s && s.getListenerId() != null && !s.getListenerId().isEmpty()) {
            return s.getListenerId();
        }
        return listener.getClass().getName();
    }

    private void record(Long savegameId, Long cycleEventId, String stepKey, String listenerId, CycleStepStatus status,
                        String error) {
        CycleStep step = steps.findByCycleEventIdAndStepKeyAndListenerId(cycleEventId, stepKey, listenerId)
                .orElseGet(() -> {
                    CycleStep n = new CycleStep();
                    n.setSavegame(savegames.getReferenceById(savegameId));
                    n.setCycleEventId(cycleEventId);
                    n.setStepKey(stepKey);
                    n.setListenerId(listenerId);
                    return n;
                });
        step.setStatus(status);
        step.setAttempts(step.getAttempts() + 1);
        step.setLastError(error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error);
        step.setUpdatedAt(Instant.now());
        steps.save(step);
    }

    /** One open notice per listener and event type is enough (a listener of every cycle would raise one per day). */
    private void notifySkipped(Long savegameId, String listenerId, Object payload, RuntimeException e) {
        Savegame sg = savegames.findById(savegameId).orElseThrow();
        String listener = shortName(listenerId);
        String type = payload.getClass().getSimpleName();
        boolean open = notices.open(sg).stream().filter(n -> n.getKind() == NoticeKind.CYCLE_STEP_SKIPPED)
                .map(notices::details)
                .anyMatch(d -> listener.equals(d.get("listener")) && type.equals(d.get("event")));
        if (open) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("listener", listener);
        details.put("event", type);
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        details.put("error", error.length() > 300 ? error.substring(0, 300) + "…" : error);
        notices.raise(sg, NoticeKind.CYCLE_STEP_SKIPPED, details, null, null);
    }

    /** {@code de.farmpulse.rpsim.newspaper.VillageNewspaperService.onDay(...)} -> {@code VillageNewspaperService.onDay}. */
    static String shortName(String listenerId) {
        String s = listenerId;
        int paren = s.indexOf('(');
        if (paren >= 0) {
            s = s.substring(0, paren);
        }
        int lastDot = s.lastIndexOf('.');
        int classDot = lastDot > 0 ? s.lastIndexOf('.', lastDot - 1) : -1;
        String shortened = classDot >= 0 ? s.substring(classDot + 1) : s;
        int dollar = shortened.lastIndexOf('$');
        return dollar >= 0 ? shortened.substring(dollar + 1) : shortened;
    }
}
