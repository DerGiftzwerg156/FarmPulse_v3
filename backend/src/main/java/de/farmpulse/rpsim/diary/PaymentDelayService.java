package de.farmpulse.rpsim.diary;

import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.PaymentDelayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-T1: records payment delays for the milestone "year without payment delay" (owner decision: a missed
 * loan installment, an overdue tax bill, a missed contract payment, an overdue salary, an unpaid claim after the sale
 * of a pledged field). No other formula reads them.
 */
@Service
public class PaymentDelayService {

    private final PaymentDelayRepository delays;

    public PaymentDelayService(PaymentDelayRepository delays) {
        this.delays = delays;
    }

    @Transactional
    public void record(Savegame sg, String kind, long gameTime) {
        PaymentDelay d = new PaymentDelay();
        d.setSavegame(sg);
        d.setKind(kind);
        d.setGameTime(gameTime);
        delays.save(d);
    }

    /** True when a delay lies in [from, to). */
    public boolean any(Savegame sg, long from, long to) {
        return delays.existsBySavegameAndGameTimeGreaterThanEqualAndGameTimeLessThan(sg, from, to);
    }
}
