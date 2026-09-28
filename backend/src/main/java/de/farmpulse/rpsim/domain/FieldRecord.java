package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Roadmap V2 R2-C: one own field (farmland) with its derived growth phase and the timers of the village reactions (C4)
 * and field work hints (C6).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_record")
public class FieldRecord extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    /** Field name as shown in the game (field:getName()). */
    @Column(name = "field_name", length = 64)
    private String fieldName;

    @Column(name = "fruit_type", length = 64)
    private String fruitType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "phase", nullable = false, length = 16)
    private FieldPhase phase;

    @Column(name = "phase_since_game_time", nullable = false)
    private long phaseSinceGameTime;

    @Column(name = "last_seen_game_time", nullable = false)
    private long lastSeenGameTime;

    /** C4: since when the weeds / stones are above the threshold (null = not). */
    @Column(name = "weeds_high_since")
    private Long weedsHighSince;

    @Column(name = "stones_high_since")
    private Long stonesHighSince;

    /** C4: messages of the neighbor in the current weeds / stones episode (1 = friendly, 2 = annoyed). */
    @Column(name = "neighbor_warnings", nullable = false)
    private int neighborWarnings;

    @Column(name = "last_neighbor_warning_game_time")
    private Long lastNeighborWarningGameTime;

    /** C4: gossip about this fallow / withered episode already sent. */
    @Column(name = "fallow_gossip_sent", nullable = false)
    private boolean fallowGossipSent;

    @Column(name = "withered_gossip_sent", nullable = false)
    private boolean witheredGossipSent;

    /** C6: hints already given for the current episode (reset when the state changes). */
    @Column(name = "harvest_hint_sent", nullable = false)
    private boolean harvestHintSent;

    @Column(name = "lime_hint_sent", nullable = false)
    private boolean limeHintSent;

    @Column(name = "plow_hint_sent", nullable = false)
    private boolean plowHintSent;
}
