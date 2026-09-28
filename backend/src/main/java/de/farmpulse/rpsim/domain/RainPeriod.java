package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V2 R2-C2: rain time and observed time of one game month, extrapolated from the weather samples. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "rain_period")
public class RainPeriod extends SavegameScoped {

    @Column(name = "month_index", nullable = false)
    private long monthIndex;

    @Column(name = "rain_ms", nullable = false)
    private long rainMs;

    @Column(name = "observed_ms", nullable = false)
    private long observedMs;

    public double rainShare() {
        return observedMs <= 0 ? 0 : rainMs / (double) observedMs;
    }
}
