package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Negotiation process (asset type, direction, status, round counter). Generic for tradeable goods; V1: FARMLAND. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "negotiation")
public class Negotiation extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "asset_type", nullable = false, length = 32)
    private AssetType assetType;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private NegotiationKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "direction", nullable = false, length = 32)
    private NegotiationDirection direction;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "initiated_by", nullable = false, length = 32)
    private Initiator initiatedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private NegotiationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterpart_character_id")
    private Character counterpartCharacter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "announcing_character_id")
    private Character announcingCharacter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_character_id")
    private Character winnerCharacter;

    @Column(name = "base_price", nullable = false)
    private long basePrice;

    @Column(name = "asking_price")
    private Long askingPrice;

    @Column(name = "rounds_used", nullable = false)
    private int roundsUsed;

    @Column(name = "max_rounds", nullable = false)
    private int maxRounds;

    @Column(name = "last_counter_offer")
    private Long lastCounterOffer;

    @Column(name = "final_price")
    private Long finalPrice;

    @Column(name = "opened_at_game_time", nullable = false)
    private long openedAtGameTime;

    @Column(name = "closes_at_game_time")
    private Long closesAtGameTime;

    @Column(name = "closed_at_game_time")
    private Long closedAtGameTime;

    @Column(name = "sale_group_id", length = 64)
    private String saleGroupId;
}
