package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-F4: last warning mail per animal type and food group. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feed_alert")
public class FeedAlert extends SavegameScoped {

    @Column(name = "animal_type", nullable = false, length = 64)
    private String animalType;

    @Column(name = "group_title", nullable = false, length = 120)
    private String groupTitle;

    @Column(name = "last_mail_game_time")
    private Long lastMailGameTime;
}
