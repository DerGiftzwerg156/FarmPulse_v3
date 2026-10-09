package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D6: guest income of one month with its factors and complaints. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "farm_holiday_month")
public class FarmHolidayMonth extends SavegameScoped {

    /** The month the guests stayed (paid at the next month start). */
    @Column(name = "month_index", nullable = false)
    private long monthIndex;

    @Column(name = "period", nullable = false)
    private int period;

    @Column(name = "income", nullable = false)
    private long income;

    @Column(name = "season_factor", nullable = false)
    private double seasonFactor;

    @Column(name = "reputation_factor", nullable = false)
    private double reputationFactor;

    @Column(name = "animal_factor", nullable = false)
    private double animalFactor;

    @Column(name = "noise", nullable = false)
    private boolean noise;

    @Column(name = "smell", nullable = false)
    private boolean smell;

    @Column(name = "bad_review", nullable = false)
    private boolean badReview;

    @Column(name = "game_time", nullable = false)
    private long gameTime;
}
