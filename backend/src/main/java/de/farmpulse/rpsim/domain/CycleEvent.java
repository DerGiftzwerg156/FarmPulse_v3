package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Technical review 10/2026, Phase 1.2/1.3: an event of the bridge cycle that still has to reach its listeners (or the
 * game-time work of a farm_facts snapshot). Written in the same transaction as the read that caused it; deleted once
 * every listener is done or skipped. Processed strictly in id order per savegame.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cycle_event")
public class CycleEvent extends SavegameScoped {

    /** Fully qualified class of the payload (an allow-listed record). */
    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
