package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Base of every entity below the aggregate root: each row belongs to exactly one {@link Savegame}. Carries the
 * optimistic-locking version of all these entities.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class SavegameScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Technical review 10/2026, Phase 1.4 (R-2): optimistic locking - an outdated write fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "savegame_id")
    private Savegame savegame;
}
