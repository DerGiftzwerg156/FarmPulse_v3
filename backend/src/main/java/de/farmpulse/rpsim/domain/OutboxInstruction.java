package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Backing store of instructions.json (one writer: the backend). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "outbox_instruction")
public class OutboxInstruction extends SavegameScoped {

    @Column(name = "instruction_id", nullable = false, unique = true, length = 64)
    private String instructionId;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 32)
    private InstructionType type;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private InstructionStatus status;

    @Column(name = "game_time_earliest")
    private Long gameTimeEarliest;

    @Column(name = "created_at_game_time", nullable = false)
    private long createdAtGameTime;

    @Column(name = "acked_at_game_time")
    private Long ackedAtGameTime;

    @Column(name = "ack_message", length = 1000)
    private String ackMessage;

    @Column(name = "related_entity_type", length = 64)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private Long relatedEntityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
