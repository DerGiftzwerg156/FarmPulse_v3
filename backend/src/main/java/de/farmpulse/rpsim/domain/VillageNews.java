package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D1: a public event dropped by a service for the next newspaper (gossip, visits, theft ...). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "village_news")
public class VillageNews extends SavegameScoped {

    @Column(name = "section", nullable = false, length = 32)
    private String section;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    /** Plain fact line (no private money matters). */
    @Column(name = "text", nullable = false, length = 500)
    private String text;

    @Column(name = "game_time", nullable = false)
    private long gameTime;
}
