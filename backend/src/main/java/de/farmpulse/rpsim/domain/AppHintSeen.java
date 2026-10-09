package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * First-open hint of a Hof-Tablet app that was confirmed (owner decision 2026-10-06): once per installation, on any
 * device; not part of a savegame. The id is the app id of the frontend ({@code layout/apps.ts}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "app_hint_seen")
public class AppHintSeen {

    @Id
    @Column(name = "app_id", length = 32)
    private String appId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;
}
