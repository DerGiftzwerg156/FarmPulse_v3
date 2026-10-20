package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-O1: a delivery point the player set in the game by key - position and facing direction,
 * the farmland there and when it was set (farm_facts.deliveryPoints, numbered by the mod with {@code seq}). The name
 * and the management live only here; {@code deleted} keeps the mod's next export from bringing the point back.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_delivery_point")
public class DealerDeliveryPoint extends SavegameScoped {

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "x", nullable = false)
    private double x;

    @Column(name = "y", nullable = false)
    private double y;

    @Column(name = "z", nullable = false)
    private double z;

    @Column(name = "ry", nullable = false)
    private double ry;

    @Column(name = "farmland_id")
    private Integer farmlandId;

    @Column(name = "set_day")
    private Integer setDay;

    @Column(name = "set_day_time_ms")
    private Long setDayTimeMs;

    /** null until the player names the point ("Lieferort <seq>" in the tablet). */
    @Column(name = "name", length = 40)
    private String name;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;
}
