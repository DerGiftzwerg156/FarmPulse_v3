package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D7: mean of the exported prices of a fill type in an FS25 year (one sample per game day). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "coop_price_year")
public class CoopPriceYear extends SavegameScoped {

    @Column(name = "crop_year", nullable = false)
    private int cropYear;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "price_sum", nullable = false)
    private double priceSum;

    @Column(name = "samples", nullable = false)
    private int samples;

    @Column(name = "last_day", nullable = false)
    private long lastDay;
}
