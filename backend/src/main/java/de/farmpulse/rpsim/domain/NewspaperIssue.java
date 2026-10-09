package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D1: one issue of the village newspaper "Dorfblatt" (facts since {@code fromGameTime}). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "newspaper_issue")
public class NewspaperIssue extends SavegameScoped {

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(name = "month_index", nullable = false)
    private long monthIndex;

    /** The extra issue at the middle of the month. */
    @Column(name = "mid_month", nullable = false)
    private boolean midMonth;

    @Column(name = "period")
    private Integer period;

    @Column(name = "crop_year")
    private Integer cropYear;

    @Column(name = "from_game_time", nullable = false)
    private long fromGameTime;

    @Column(name = "published_game_time", nullable = false)
    private long publishedGameTime;

    /** Headline of the first article once written (chronicle). */
    @Column(name = "headline", length = 255)
    private String headline;
}
