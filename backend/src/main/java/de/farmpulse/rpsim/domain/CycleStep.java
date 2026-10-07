package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Technical review 10/2026, Phase 1.3: journal entry of one listener for one (sub step of a) queued cycle event. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cycle_step")
public class CycleStep extends SavegameScoped {

    @Column(name = "cycle_event_id", nullable = false)
    private Long cycleEventId;

    /** Sub step of the event, e.g. {@code day:12} of the game-time work; empty for plain events. */
    @Column(name = "step_key", nullable = false, length = 100)
    private String stepKey;

    @Column(name = "listener_id", nullable = false, length = 500)
    private String listenerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CycleStepStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
