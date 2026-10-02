package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Roadmap V3 R3-T1: a reached milestone (once per savegame). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "milestone")
public class Milestone extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "milestone_key", nullable = false, length = 32)
    private MilestoneKey milestoneKey;

    @Column(name = "reached_game_time", nullable = false)
    private long reachedGameTime;

    @Column(name = "diary_entry_id")
    private Long diaryEntryId;
}
