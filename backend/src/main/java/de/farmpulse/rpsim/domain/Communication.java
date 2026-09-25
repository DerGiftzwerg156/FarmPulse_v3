package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Unified mail AND call (channel + initiatedBy instead of two tables). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "communication")
public class Communication extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "initiated_by", nullable = false, length = 16)
    private CommunicationInitiator initiatedBy;

    @Column(name = "subject", length = 500)
    private String subject;

    @Lob
    @Column(name = "body")
    private String body;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_flag", nullable = false)
    private boolean readFlag;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private CommunicationCategory category;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "related_entity_type", length = 64)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private Long relatedEntityId;

    @Column(name = "thread_root_id")
    private Long threadRootId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "call_status", length = 16)
    private CallStatus callStatus;

    @Column(name = "ring_deadline_game_time")
    private Long ringDeadlineGameTime;

    @Column(name = "open_topic", nullable = false)
    private boolean openTopic;

    @Column(name = "used_fallback", nullable = false)
    private boolean usedFallback;

    @Column(name = "form_link", length = 255)
    private String formLink;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tone_class", length = 16)
    private ToneClass toneClass;
}
