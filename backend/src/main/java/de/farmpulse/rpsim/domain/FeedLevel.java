package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-F2: last seen level of a trough, the straw of a stable or the silo stock of a fill type. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "feed_level")
public class FeedLevel extends SavegameScoped {

    @Column(name = "ref", nullable = false, length = 160)
    private String ref;

    @Column(name = "amount", nullable = false)
    private double amount;
}
