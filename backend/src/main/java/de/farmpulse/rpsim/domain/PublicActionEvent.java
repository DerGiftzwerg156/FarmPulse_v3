package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Savegame-wide log of public actions for the village reputation. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "public_action_event")
public class PublicActionEvent extends SavegameScoped {

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 32)
    private PublicActionType type;

    @Column(name = "delta", nullable = false)
    private double delta;

    @Column(name = "note", length = 500)
    private String note;
}
