package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Decouples the fact layer from the AI: holds finished facts (categories only), a worker turns them into a Communication. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "narration_job")
public class NarrationJob extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private CommunicationCategory category;

    @Lob
    @Column(name = "facts_json", nullable = false)
    private String factsJson;

    @Lob
    @Column(name = "player_message")
    private String playerMessage;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private NarrationJobStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "not_before_game_time", nullable = false)
    private long notBeforeGameTime;

    @Column(name = "related_entity_type", length = 64)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private Long relatedEntityId;

    @Column(name = "thread_root_id")
    private Long threadRootId;

    @Column(name = "communication_id")
    private Long communicationId;

    @Column(name = "used_fallback", nullable = false)
    private boolean usedFallback;

    /** Roadmap V3.1 R31-D1 / R31-D2: the text goes into this target (newspaper article, chat message), not a mail. */
    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "form_link", length = 255)
    private String formLink;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Technical review 10/2026, Phase 1.6: while IN_PROGRESS the claim ends here; afterwards the job is claimed again. */
    @Column(name = "lease_until")
    private Instant leaseUntil;
}
