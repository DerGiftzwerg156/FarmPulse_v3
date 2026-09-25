package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Aggregate root. Every other entity references it; nothing is reused across savegames. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "savegame")
public class Savegame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bridge_savegame_id", unique = true, length = 255)
    private String bridgeSavegameId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private SavegameStatus status;

    @Column(name = "map_name", length = 255)
    private String mapName;

    @Column(name = "current_game_time", nullable = false)
    private long currentGameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tone_preset", nullable = false, length = 32)
    private TonePreset tonePreset;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "farm_origin", length = 32)
    private FarmOrigin farmOrigin;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "village_relation", length = 32)
    private VillageRelation villageRelation;

    @Lob
    @Column(name = "backstory_free_text")
    private String backstoryFreeText;

    @Column(name = "free_text_rejected", nullable = false)
    private boolean freeTextRejected;

    @Column(name = "starting_capital_target", nullable = false)
    private long startingCapitalTarget;

    @Column(name = "starting_capital_adjusted", nullable = false)
    private boolean startingCapitalAdjusted;

    @Column(name = "legacy_loan_amount")
    private Long legacyLoanAmount;

    @Lob
    @Column(name = "initial_employees_json")
    private String initialEmployeesJson;

    @Column(name = "dynamic_rotations_this_year", nullable = false)
    private int dynamicRotationsThisYear;

    @Column(name = "rotation_year_index", nullable = false)
    private int rotationYearIndex;

    @Column(name = "last_processed_game_day")
    private Long lastProcessedGameDay;

    @Column(name = "first_game_time")
    private Long firstGameTime;

    @Lob
    @Column(name = "market_context_json")
    private String marketContextJson;

    @Column(name = "generation_seed", nullable = false)
    private long generationSeed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "last_congratulation_game_time")
    private Long lastCongratulationGameTime;

    @Column(name = "last_gossip_game_time")
    private Long lastGossipGameTime;
}
