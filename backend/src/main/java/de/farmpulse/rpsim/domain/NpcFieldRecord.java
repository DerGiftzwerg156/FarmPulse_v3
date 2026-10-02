package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Roadmap V3 R3-H1: a field the game's NPCs farm (farm_facts.npcFields) with the derived growth phase - the neighbour
 * counterpart of {@link FieldRecord}. Its harvests feed the stock of the neighbour who owns the farmland in the tool.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "npc_field_record")
public class NpcFieldRecord extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "field_name", length = 64)
    private String fieldName;

    @Column(name = "hectares")
    private Double hectares;

    @Column(name = "fruit_type", length = 64)
    private String fruitType;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    @Column(name = "liters_per_sqm")
    private Double litersPerSqm;

    @Column(name = "plow_level")
    private Integer plowLevel;

    @Column(name = "stone_level")
    private Integer stoneLevel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "phase", nullable = false, length = 16)
    private FieldPhase phase;

    @Column(name = "phase_since_game_time", nullable = false)
    private long phaseSinceGameTime;

    @Column(name = "last_seen_game_time", nullable = false)
    private long lastSeenGameTime;
}
