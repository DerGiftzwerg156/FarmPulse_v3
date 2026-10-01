package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-M1: price alarm of the Agrarbörse - fires once when the price (EUR per 1000 l) of a fill type at a sell
 * point (null = the best price of all sell points) reaches the threshold in the given direction; can be re-activated.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "price_alarm")
public class PriceAlarm extends SavegameScoped {

    public static final String ACTIVE = "ACTIVE";
    public static final String FIRED = "FIRED";
    public static final String ABOVE = "ABOVE";
    public static final String BELOW = "BELOW";

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "sell_point", length = 128)
    private String sellPoint;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "fired_game_time")
    private Long firedGameTime;

    @Column(name = "fired_price")
    private Double firedPrice;

    @Column(name = "fired_sell_point", length = 128)
    private String firedSellPoint;
}
