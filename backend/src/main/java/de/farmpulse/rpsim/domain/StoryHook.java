package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Onboarding story hooks, scheduled staggered over the first game weeks. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "story_hook")
public class StoryHook extends SavegameScoped {

    @Column(name = "hook_key", nullable = false, length = 64)
    private String hookKey;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "scheduled_game_time", nullable = false)
    private long scheduledGameTime;

    @Column(name = "fired", nullable = false)
    private boolean fired;

    @Column(name = "fired_at_game_time")
    private Long firedAtGameTime;
}
