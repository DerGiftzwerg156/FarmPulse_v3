package de.farmpulse.rpsim.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V2 R2-E3: family, retirement, occasions, family field. A game month is one game day here. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FamilyServiceTest {

    @Autowired Fixtures fx;
    @Autowired FamilyService family;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired TrustEventRepository trustEvents;
    @Autowired VillageReputationService reputation;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private void month(int day) {
        long t = GameTime.days(day);
        sg.setCurrentGameTime(t);
        family.onMonth(new GameMonthPassedEvent(sg.getId(), day, t));
    }

    @Test
    void theSwitchesChooseTheFamilyAndAnInheritedFarmPaysTheRetirement() {
        sg.setFarmOrigin(FarmOrigin.INHERITED);
        List<Character> members = family.create(sg, new FamilyService.Choice(true, true, false));
        assertThat(members).extracting(Character::getAffiliation).containsExactly("PARENT", "PARENT", "PARTNER");
        assertThat(members.get(0).getName().substring(members.get(0).getName().lastIndexOf(' ')))
                .as("one family name").isEqualTo(members.get(1).getName().substring(members.get(1).getName().lastIndexOf(' ')));
        assertThat(members).allMatch(c -> c.getOccasionPeriod() != null);
        assertThat(sg.getRetirementPayment()).isEqualTo(800);
        month(20);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("\"amount\":-800")
                && o.getPayloadJson().contains("FAMILY"));
        assertThat(reputation.trustAverage(sg)).as("the family is not the village").isZero();
    }

    @Test
    void aFreshStartHasParentsWithoutRetirementAndAloneMeansNobody() {
        sg.setFarmOrigin(FarmOrigin.BOUGHT_FRESH_START);
        assertThat(family.create(sg, new FamilyService.Choice(true, false, true)))
                .extracting(Character::getAffiliation).startsWith("PARENT_AWAY", "PARENT_AWAY").contains("CHILD");
        assertThat(sg.getRetirementPayment()).isNull();
        Savegame alone = fx.savegame();
        assertThat(family.create(alone, FamilyService.Choice.NONE)).isEmpty();
    }

    @Test
    void birthdaysComeInTheirPeriod() {
        List<Character> members = family.create(sg, new FamilyService.Choice(false, true, false));
        int period = members.getFirst().getOccasionPeriod();
        // fallback calendar: day d = period ((d % 12) + 1)
        month(period - 1 + 24);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).anyMatch(j -> j.getEventType().equals("FAMILY_OCCASION")
                && j.getFactsJson().contains("BIRTHDAY"));
    }

    @Test
    void sellingTheFamilyFieldCostsTheTrustOfTheWholeFamily() {
        List<Character> members = family.create(sg, new FamilyService.Choice(true, false, false));
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        fx.snapshot(sg, 100_000); // own farmland 12
        ownership.reconcile(sg);
        assertThatThrownBy(() -> family.markFamilyField(sg, 13)).isInstanceOf(BusinessRuleException.class);
        family.markFamilyField(sg, 12);
        var s = fx.snapshot(sg, sg.getCurrentGameTime() + 1, 100_000, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime() + 1, 100_000).replace("\"farmlandId\": 12", "\"farmlandId\": 14"));
        family.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), sg.getCurrentGameTime() + 1, false));
        assertThat(messages()).contains("FAMILY_FIELD_SOLD");
        assertThat(sg.getFamilyFieldId()).isNull();
        for (Character c : members) {
            assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c))
                    .anyMatch(e -> e.getReason() == TrustReason.FAMILY_FIELD_SOLD && e.getDelta() == -15);
        }
    }
}
