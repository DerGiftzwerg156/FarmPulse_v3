package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentDelayRepository extends JpaRepository<PaymentDelay, Long> {

    /** True when a delay lies in [from, to). */
    boolean existsBySavegameAndGameTimeGreaterThanEqualAndGameTimeLessThan(Savegame savegame, long from, long to);
}
