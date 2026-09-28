package de.farmpulse.rpsim.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V2 R2-F1: an answer read from export/player_responses.json. The responseId makes the processing idempotent -
 * the mod keeps an answer in the file until the backend acknowledges it (ackedResponses).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "player_response")
public class PlayerResponse extends SavegameScoped {

    @Column(name = "response_id", nullable = false, length = 96)
    private String responseId;

    @Column(name = "prompt_id", nullable = false, length = 64)
    private String promptId;

    @Column(name = "answer", nullable = false, length = 32)
    private String answer;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
