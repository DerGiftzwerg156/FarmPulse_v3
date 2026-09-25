package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Price event (incl. fixed-price special contracts, subsidies and rumours). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "market_event")
public class MarketEvent extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 32)
    private MarketEventType eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private MarketEventStatus status;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    @Column(name = "sell_point", length = 255)
    private String sellPoint;

    @Column(name = "peak_multiplier")
    private Double peakMultiplier;

    @Column(name = "ramp_up_hours")
    private Double rampUpHours;

    @Column(name = "hold_hours")
    private Double holdHours;

    @Column(name = "decay_hours")
    private Double decayHours;

    @Column(name = "fixed_price")
    private Long fixedPrice;

    @Column(name = "max_quantity")
    private Long maxQuantity;

    @Column(name = "deadline_game_time")
    private Long deadlineGameTime;

    @Column(name = "subsidy_amount")
    private Long subsidyAmount;

    @Column(name = "start_game_time", nullable = false)
    private long startGameTime;

    @Column(name = "end_game_time")
    private Long endGameTime;

    @Column(name = "is_accurate")
    private Boolean isAccurate;

    @Column(name = "referenced_event_id")
    private Long referencedEventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "delivered_quantity")
    private Long deliveredQuantity;

    @Column(name = "end_reason", length = 64)
    private String endReason;

    @Column(name = "player_participation")
    private Boolean playerParticipation;

    @Column(name = "announced_at_game_time", nullable = false)
    private long announcedAtGameTime;
}
