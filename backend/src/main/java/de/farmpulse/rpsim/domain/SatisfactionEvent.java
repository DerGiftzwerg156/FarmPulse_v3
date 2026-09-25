package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Satisfaction log per employee. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "satisfaction_event")
public class SatisfactionEvent extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private SatisfactionCategory category;

    @Column(name = "delta", nullable = false)
    private double delta;

    @Column(name = "reason", length = 255)
    private String reason;
}
