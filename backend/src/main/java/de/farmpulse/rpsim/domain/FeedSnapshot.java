package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-F1: the totals of a husbandry at one game time (JSON fill type -> litres) with the need of the game. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feed_snapshot")
public class FeedSnapshot extends SavegameScoped {

    @Column(name = "husbandry_unique_id", nullable = false, length = 80)
    private String husbandryUniqueId;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "liters_per_hour")
    private Double litersPerHour;

    @Column(name = "totals_json", nullable = false, length = 4000)
    private String totalsJson;
}
