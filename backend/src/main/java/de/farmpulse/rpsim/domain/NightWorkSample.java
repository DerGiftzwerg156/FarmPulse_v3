package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D4: night time of running helpers counted from one export. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "night_work_sample")
public class NightWorkSample extends SavegameScoped {

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "night_ms", nullable = false)
    private long nightMs;
}
