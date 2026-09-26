package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One simulated incident or offer (storm/hail/wildlife damage, vet visit, trader offer, repair ...). All amounts are
 * formula results of the fact layer; the character only narrates them.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "service_case")
public class ServiceCase extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private CaseKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "farmland_id")
    private Integer farmlandId;

    @Column(name = "hectares")
    private Double hectares;

    /** Loss caused by the incident (booked as DAMAGE). */
    @Column(name = "damage_amount")
    private Long damageAmount;

    /** Money to the player (insurance payout, compensation, premium). */
    @Column(name = "payout_amount")
    private Long payoutAmount;

    /** Money from the player (vet invoice, repair ...). */
    @Column(name = "cost_amount")
    private Long costAmount;

    /** Current offer of the character (e.g. wildlife compensation). */
    @Column(name = "offer_amount")
    private Long offerAmount;

    @Column(name = "rounds_used", nullable = false)
    private int roundsUsed;

    @Column(name = "measure_agreed", nullable = false)
    private boolean measureAgreed;

    /** Free reference, e.g. husbandry / vehicle uniqueId or mission id. */
    @Column(name = "reference", length = 255)
    private String reference;

    @Column(name = "contract_id")
    private Long contractId;

    /** Livestock offers: number of animals, direction SELL / BUY and the head count when the offer was accepted. */
    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "direction", length = 8)
    private String direction;

    @Column(name = "baseline_count")
    private Integer baselineCount;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "deadline_game_time")
    private Long deadlineGameTime;

    @Column(name = "closed_at_game_time")
    private Long closedAtGameTime;

    @Column(name = "resolution", length = 64)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
