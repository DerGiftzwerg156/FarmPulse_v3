package de.farmpulse.rpsim.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class MemoryServiceTest {

    @Autowired Fixtures fx;
    @Autowired MemoryService memory;
    @Autowired TrustScoreService trust;
    @Autowired CreditApplicationService credit;

    @Test
    void derivesStableChronologicalShortFactsLimitedToN() {
        Savegame sg = fx.savegame();
        Character bank = fx.bank(sg);
        fx.snapshot(sg, 1_000_000);
        trust.recordEvent(bank, 2, TrustReason.ON_TIME_PAYMENT, null);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1));
        CreditApplication a = credit.submit(sg, 30_000, "Stall", 24);
        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        credit.releaseVisibleDecisions(sg);
        trust.recordEvent(bank, -2, TrustReason.TONE_RUDE, null);

        List<String> facts = memory.shortFacts(bank);
        assertThat(facts).hasSize(3);
        assertThat(facts.get(0)).isEqualTo("Spieltag 10: Rate pünktlich bezahlt");
        assertThat(facts.get(1)).contains("Kreditantrag über 30000 € für \"Stall\" genehmigt");
        assertThat(facts.get(2)).endsWith("schroffe Nachricht erhalten");
        assertThat(memory.shortFacts(bank)).isEqualTo(facts); // stable
        assertThat(String.join(" ", facts)).doesNotContainIgnoringCase("score").doesNotContain("trust");

        for (int i = 0; i < 20; i++) {
            sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1));
            trust.recordEvent(bank, 1, TrustReason.PROMISE_KEPT, null);
        }
        List<String> limited = memory.shortFacts(bank);
        assertThat(limited).hasSize(8).allMatch(f -> f.endsWith("Zusage eingehalten")); // newest 8 only
    }
}
