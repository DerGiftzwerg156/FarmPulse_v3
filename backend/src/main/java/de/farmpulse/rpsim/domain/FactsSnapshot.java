package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Time series of farm_facts.json (raw states only) - basis for cash-flow trend, storage and price history. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "facts_snapshot")
public class FactsSnapshot extends SavegameScoped {

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Lob
    @Column(name = "raw_json", nullable = false)
    private String rawJson;
}
