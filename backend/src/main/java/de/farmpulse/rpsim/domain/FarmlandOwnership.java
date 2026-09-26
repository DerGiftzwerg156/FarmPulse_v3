package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Owner per farmland (PLAYER / Character / UNCLAIMED) and reference price - basis for credit scoring and negotiation. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "farmland_ownership")
public class FarmlandOwnership extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "owner_type", nullable = false, length = 32)
    private OwnerType ownerType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_character_id")
    private Character ownerCharacter;

    @Column(name = "reference_price", nullable = false)
    private long referencePrice;

    @Column(name = "hectares", nullable = false)
    private double hectares;

    @Column(name = "updated_at_game_time", nullable = false)
    private long updatedAtGameTime;

    /**
     * TODO T-11: false for farmlands the vanilla farmland menu hides (showOnFarmlandsScreen = false, e.g. village,
     * roads) or that belong to the map's default farm property. Such fields are never given to NPCs, auctioned or
     * negotiated.
     */
    @Column(name = "tradeable", nullable = false)
    private boolean tradeable = true;
}
