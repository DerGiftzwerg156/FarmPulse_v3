package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3 R3-K3: farm report of a finished FS25 year; the figures (and the key figures for the next comparison) as JSON. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "farm_report")
public class FarmReport extends SavegameScoped {

    @Column(name = "report_year", nullable = false)
    private int reportYear;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Lob
    @Column(name = "report_json", nullable = false)
    private String reportJson;
}
