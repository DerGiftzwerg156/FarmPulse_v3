package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Offer history per round. hiddenMaxBid is internal (NPC limit) and never exposed. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "negotiation_offer")
public class NegotiationOffer extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "negotiation_id")
    private Negotiation negotiation;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "offered_by", nullable = false, length = 32)
    private OfferParty offeredBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "result", nullable = false, length = 32)
    private OfferResult result;

    @Column(name = "counter_amount")
    private Long counterAmount;

    @Column(name = "hidden_max_bid")
    private Long hiddenMaxBid;

    @Column(name = "game_time", nullable = false)
    private long gameTime;
}
