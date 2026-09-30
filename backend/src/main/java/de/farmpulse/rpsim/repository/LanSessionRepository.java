package de.farmpulse.rpsim.repository;

import java.time.Instant;
import java.util.Optional;

import de.farmpulse.rpsim.domain.LanSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LanSessionRepository extends JpaRepository<LanSession, Long> {

    Optional<LanSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from LanSession s where s.expiresAt < :now")
    int deleteExpired(Instant now);
}
