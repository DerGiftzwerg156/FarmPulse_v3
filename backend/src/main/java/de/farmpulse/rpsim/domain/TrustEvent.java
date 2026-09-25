package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only log that drives Character.trustScore. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "trust_event")
public class TrustEvent extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "delta", nullable = false)
    private double delta;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", nullable = false, length = 32)
    private TrustReason reason;

    @Column(name = "note", length = 500)
    private String note;
}
