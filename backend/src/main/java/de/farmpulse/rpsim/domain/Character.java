package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Character with fact layer (role, status, trust, hard data) and personality layer (name, traits, style, backstory). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "game_character")
public class Character extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 32)
    private CharacterRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 32)
    private CharacterCategory category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CharacterStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "termination_reason", length = 32)
    private TerminationReason terminationReason;

    @Column(name = "trust_score", nullable = false)
    private double trustScore;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "traits", length = 1000)
    private String traits;

    @Column(name = "speech_style", length = 500)
    private String speechStyle;

    @Lob
    @Column(name = "backstory")
    private String backstory;

    @Column(name = "short_description", length = 1000)
    private String shortDescription;

    @Column(name = "relationships", length = 1000)
    private String relationships;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "negotiation_trait", nullable = false, length = 32)
    private NegotiationTrait negotiationTrait;

    @Column(name = "virtual_wealth", nullable = false)
    private double virtualWealth;

    @Column(name = "sell_willing", nullable = false)
    private boolean sellWilling;

    @Column(name = "generation_seed", nullable = false)
    private long generationSeed;

    @Column(name = "substitute_for_id")
    private Long substituteForId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "absence_variant", length = 32)
    private AbsenceVariant absenceVariant;

    @Column(name = "on_leave_until_game_time")
    private Long onLeaveUntilGameTime;

    @Column(name = "last_trust_event_game_time")
    private Long lastTrustEventGameTime;

    @Column(name = "last_paced_message_game_time")
    private Long lastPacedMessageGameTime;

    @Column(name = "joined_at_game_time")
    private Long joinedAtGameTime;

    @Column(name = "left_at_game_time")
    private Long leftAtGameTime;

    @Column(name = "ai_enriched", nullable = false)
    private boolean aiEnriched;

    /** TODO T-21: index of the FS25 NPC this character stands for (farmland owner of the map), null otherwise. */
    @Column(name = "fs25_npc_index")
    private Integer fs25NpcIndex;
}
