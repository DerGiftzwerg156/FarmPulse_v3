package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-F1: eaten litres of one fill type in one husbandry since the backend knows it; last_counter = last value of
 * the mod counter (null = older mod), last_trough = last trough level (fallback without the counter). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feed_counter")
public class FeedCounter extends SavegameScoped {

    @Column(name = "husbandry_unique_id", nullable = false, length = 80)
    private String husbandryUniqueId;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "last_counter")
    private Double lastCounter;

    @Column(name = "last_trough")
    private Double lastTrough;

    @Column(name = "total", nullable = false)
    private double total;
}
