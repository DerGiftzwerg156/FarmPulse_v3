package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A detected game-time rewind: the player loaded an older state of the savegame (typically: quit without saving).
 * Bookings acknowledged after the reloaded point are missing in the game and are re-sent (T-02).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bridge_rewind")
public class BridgeRewind extends SavegameScoped {

    @Column(name = "previous_game_time", nullable = false)
    private long previousGameTime;

    @Column(name = "rewound_to_game_time", nullable = false)
    private long rewoundToGameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private RewindStatus status;

    /** Comma separated instructionIds that are missing in the reloaded savegame. */
    @Lob
    @Column(name = "lost_instruction_ids")
    private String lostInstructionIds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
