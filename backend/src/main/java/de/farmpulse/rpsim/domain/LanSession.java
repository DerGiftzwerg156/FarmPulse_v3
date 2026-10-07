package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3 R3-N2: session of a device that logged in with the PIN (only the SHA-256 of the cookie value). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lan_session")
public class LanSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Technical review 10/2026, Phase 1.4 (R-2): optimistic locking - an outdated write fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "remote_address", length = 64)
    private String remoteAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
