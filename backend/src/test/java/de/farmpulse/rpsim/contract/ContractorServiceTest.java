package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.GameNpc;
import de.farmpulse.rpsim.bridge.BridgeDtos.Mission;
import de.farmpulse.rpsim.character.GameNpcService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-22: contractor refers vanilla contracts. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ContractorServiceTest {

    @Autowired Fixtures fx;
    @Autowired ContractorService contractor;
    @Autowired GameNpcService gameNpcs;
    @Autowired ServiceCaseRepository cases;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        props.getFormulas().getContractor().setReferralProbability(1);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setContractor(new RpsimProperties.Contractor());
    }

    private static Mission mission(String id, String status, Boolean success) {
        return new Mission(id, status, "Ernte", "harvestMission", "7", 3, "Otto Wendler", 5200.0, success);
    }

    private static FarmFacts facts(Mission... missions) {
        return new FarmFacts(1, 0L, "sg", null, null, null, List.of(), null, List.of(missions));
    }

    private List<ServiceCase> referrals() {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.MISSION_REFERRAL));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void referredContractCompletedImprovesTrustWithContractorAndClient() {
        Character otto = gameNpcs.ensure(sg, new GameNpc(3, "NPC_OTTO", "Otto Wendler"));
        contractor.sync(sg, facts(mission("m1", "AVAILABLE", null)));
        ServiceCase sc = referrals().get(0);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getTitle()).isEqualTo("Ernte");
        assertThat(sc.getFarmlandId()).isEqualTo(7);
        assertThat(sc.getOfferAmount()).isEqualTo(5200);
        assertThat(narrations()).contains("CHARACTER_INTRODUCTION", "MISSION_REFERRAL");
        contractor.sync(sg, facts(mission("m1", "RUNNING", null)));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        contractor.sync(sg, facts(mission("m1", "FINISHED", true)));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("COMPLETED");
        assertThat(trustEvents.findAll()).filteredOn(t -> t.getReason() == TrustReason.MISSION_COMPLETED)
                .extracting(t -> t.getCharacter().getId())
                .containsExactlyInAnyOrder(sc.getCharacter().getId(), otto.getId());
        assertThat(narrations()).contains("MISSION_THANKS");
    }

    @Test
    void failedOrUntakenContracts() {
        contractor.sync(sg, facts(mission("m1", "AVAILABLE", null), mission("m2", "AVAILABLE", null)));
        contractor.sync(sg, facts(mission("m1", "FINISHED", false)));
        ServiceCase m1 = referrals().stream().filter(c -> "m1".equals(c.getReference())).findFirst().orElseThrow();
        ServiceCase m2 = referrals().stream().filter(c -> "m2".equals(c.getReference())).findFirst().orElseThrow();
        assertThat(m1.getResolution()).isEqualTo("FAILED");
        assertThat(narrations()).contains("MISSION_FAILED");
        assertThat(m2.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(m2.getResolution()).isEqualTo("NOT_TAKEN");
    }

    @Test
    void referralsAreLimitedAndDecidedOncePerContract() {
        contractor.sync(sg, facts(mission("a", "AVAILABLE", null), mission("b", "AVAILABLE", null),
                mission("c", "AVAILABLE", null)));
        assertThat(referrals()).hasSize(2);
        props.getFormulas().getContractor().setReferralProbability(0);
        props.getFormulas().getContractor().setMaxReferralsPerMonth(5);
        contractor.sync(sg, facts(mission("a", "AVAILABLE", null), mission("d", "AVAILABLE", null)));
        assertThat(referrals()).filteredOn(c -> "d".equals(c.getReference())).singleElement()
                .satisfies(c -> assertThat(c.getResolution()).isEqualTo("NOT_REFERRED"));
        assertThat(narrations().stream().filter("MISSION_REFERRAL"::equals)).hasSize(2);
    }

    @Test
    void fieldNumberParsing() {
        assertThat(ContractorService.fieldNumber("12")).isEqualTo(12);
        assertThat(ContractorService.fieldNumber("Nord")).isNull();
        assertThat(ContractorService.fieldNumber(null)).isNull();
    }
}
