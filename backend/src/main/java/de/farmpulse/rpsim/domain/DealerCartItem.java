package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-K3: one position of the dealer's cart - a shop entry with the player's choice of options
 * ({@code configurationsJson}: type name -> option index). The name is kept for a machine that left the catalog.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_cart_item")
public class DealerCartItem extends SavegameScoped {

    @Column(name = "xml_filename", nullable = false, length = 512)
    private String xmlFilename;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "configurations_json", nullable = false, length = 4000)
    private String configurationsJson;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;
}
