package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-N1/N2: home-network access of the installation (single row, id 1; not part of a savegame). The PIN is
 * optional and stored only as PBKDF2 hash.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lan_settings")
public class LanSettings {

    public static final long ID = 1L;

    @Id
    private Long id;

    /** Switch "Im Heimnetz erreichbar" (default off: only the gaming PC via loopback). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Base64 PBKDF2WithHmacSHA256 hash of the PIN; null = no PIN set. */
    @Column(name = "pin_hash", length = 128)
    private String pinHash;

    @Column(name = "pin_salt", length = 64)
    private String pinSalt;

    @Column(name = "pin_iterations")
    private Integer pinIterations;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
