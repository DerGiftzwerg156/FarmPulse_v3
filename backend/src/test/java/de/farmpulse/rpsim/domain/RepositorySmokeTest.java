package de.farmpulse.rpsim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToOne;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/** Save/load smoke test for every entity of the domain model (AP-3.2). */
@DataJpaTest
class RepositorySmokeTest {

    @Autowired
    EntityManager em;

    static List<Class<?>> entities() {
        return List.of(Savegame.class, Character.class, TrustEvent.class, Loan.class, LoanPayment.class,
                CreditApplication.class, MarketEvent.class, FarmlandOwnership.class, Negotiation.class,
                NegotiationOffer.class, Employee.class, SatisfactionEvent.class, JobPosting.class, JobApplication.class,
                Communication.class, OutboxInstruction.class, FactsSnapshot.class, StoryHook.class,
                PublicActionEvent.class, DiaryEntry.class, NarrationJob.class);
    }

    private int counter;

    @ParameterizedTest
    @MethodSource("entities")
    void saveAndLoad(Class<?> type) throws Exception {
        Object entity = build(type);
        em.flush();
        em.clear();
        Object id = em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
        Object loaded = em.find(type, id);
        assertThat(loaded).isNotNull();
    }

    @Test
    void everyEntityReferencesTheSavegameAggregateRoot() {
        for (Class<?> type : entities()) {
            if (type != Savegame.class) {
                assertThat(SavegameScoped.class).isAssignableFrom(type);
            }
        }
    }

    private Object build(Class<?> type) throws Exception {
        Object o = type.getDeclaredConstructor().newInstance();
        for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("id")) {
                    continue;
                }
                f.setAccessible(true);
                f.set(o, sample(f));
            }
        }
        em.persist(o);
        return o;
    }

    private Object sample(Field f) throws Exception {
        Class<?> t = f.getType();
        if (f.isAnnotationPresent(ManyToOne.class)) {
            return build(t);
        }
        counter++;
        if (t == String.class) return f.getName().endsWith("Json") ? "{}" : "v" + counter + "-" + System.nanoTime();
        if (t == long.class || t == Long.class) return (long) counter;
        if (t == int.class || t == Integer.class) return counter;
        if (t == double.class || t == Double.class) return 1.5;
        if (t == boolean.class || t == Boolean.class) return true;
        if (t == Instant.class) return Instant.now();
        if (t.isEnum()) return t.getEnumConstants()[0];
        throw new IllegalStateException("unsupported field type " + t + " of " + f);
    }
}
