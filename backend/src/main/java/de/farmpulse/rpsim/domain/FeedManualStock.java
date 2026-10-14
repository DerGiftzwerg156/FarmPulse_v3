package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-F2: stock the player entered (bales, pallets, other); liters = what is left. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feed_manual_stock")
public class FeedManualStock extends SavegameScoped {

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "liters", nullable = false)
    private double liters;

    @Column(name = "bales")
    private Integer bales;

    @Column(name = "bale_size", length = 32)
    private String baleSize;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "entered_game_time", nullable = false)
    private long enteredGameTime;

    @Column(name = "checked_game_time", nullable = false)
    private long checkedGameTime;
}
