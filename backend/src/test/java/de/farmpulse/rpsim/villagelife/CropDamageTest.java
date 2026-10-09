package de.farmpulse.rpsim.villagelife;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.CropDamageEventRepository;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-D5: crop damage on neighbour fields (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class CropDamageTest {

    @Autowired Fixtures fx;
    @Autowired CropDamageService cropDamage;
    @Autowired ContractActions actions;
    @Autowired FactsService facts;
    @Autowired FarmlandOwnershipRepository ownership;
    @Autowired CropDamageEventRepository incidents;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired JsonMapper json;

    Savegame sg;
    Character neighbor;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setBurdenCropDamage(true);
        neighbor = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Hauke Harms");
        FarmlandOwnership o = new FarmlandOwnership();
        o.setSavegame(sg);
        o.setFarmlandId(13);
        o.setOwnerType(OwnerType.CHARACTER);
        o.setOwnerCharacter(neighbor);
        o.setReferencePrice(72_000);
        o.setHectares(6);
        ownership.save(o);
    }

    private FarmFacts positions(String list) {
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000),
                "\"vehiclePositions\": [" + list + "]");
        return facts.parse(fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, doc));
    }

    private static final String ON_CROP = "{ \"uniqueId\": \"veh_00042\", \"x\": 10, \"z\": 20, \"farmlandId\": 13, \"onCrop\": true }";
    private static final String ON_STUBBLE = "{ \"uniqueId\": \"veh_00042\", \"x\": 10, \"z\": 20, \"farmlandId\": 13, \"onCrop\": false }";

    private void drive(int samples) {
        for (int i = 0; i < samples; i++) {
            cropDamage.sample(sg, positions(ON_CROP));
        }
        cropDamage.sample(sg, positions("")); // parked: the row ends
    }

    private void nextDay() {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1));
    }

    @Test
    void firstAHintThenAComplaintThenAClaimAtTheSameOwner() {
        drive(2);
        assertThat(outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.NOTIFICATION)).isEmpty(); // two are no row
        drive(3);
        assertThat(outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.NOTIFICATION)).hasSize(1); // only the hint
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).isEmpty();
        assertThat(neighbor.getTrustScore()).isZero();

        drive(4);
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).hasSize(1);
        assertThat(neighbor.getTrustScore()).isEqualTo(-3);
        drive(4);
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).hasSize(1); // one per field and game day

        nextDay();
        drive(4);
        List<ServiceCase> claims = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.CROP_DAMAGE_CLAIM));
        assertThat(claims).hasSize(1);
        ServiceCase claim = claims.getFirst();
        assertThat(claim.getOfferAmount()).isEqualTo(4 * 150);
        assertThat(claim.getCharacter().getId()).isEqualTo(neighbor.getId());
        assertThat(neighbor.getTrustScore()).isEqualTo(-6);

        actions.acceptCase(sg, claim.getId());
        assertThat(claim.getStatus()).isEqualTo(CaseStatus.SETTLED);
        var money = outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION).getLast();
        var p = json.readTree(money.getPayloadJson());
        assertThat(p.path("amount").asLong()).isEqualTo(-600);
        assertThat(p.path("reason").asString()).isEqualTo("COMPENSATION");
    }

    @Test
    void refusingTheClaimCostsTrust() {
        sg.setCropDamageHintSent(true);
        drive(3);
        nextDay();
        drive(3);
        ServiceCase claim = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.CROP_DAMAGE_CLAIM)).getFirst();
        actions.declineCase(sg, claim.getId());
        assertThat(claim.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(neighbor.getTrustScore()).isEqualTo(-3 - 3 - 5);
    }

    @Test
    void stubbleOrdersLeasesAndTheSwitchProtectTheFarm() {
        sg.setCropDamageHintSent(true);
        for (int i = 0; i < 4; i++) {
            cropDamage.sample(sg, positions(ON_STUBBLE));
        }
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).isEmpty();

        ServiceCase order = new ServiceCase();
        order.setSavegame(sg);
        order.setKind(CaseKind.NEIGHBOR_MISSION);
        order.setStatus(CaseStatus.IN_PROGRESS);
        order.setCharacter(neighbor);
        order.setFarmlandId(13);
        order.setGameTime(sg.getCurrentGameTime());
        order.setCreatedAt(Instant.now());
        cases.save(order);
        drive(4);
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).isEmpty();
        order.setStatus(CaseStatus.SETTLED);

        ownership.findBySavegameAndFarmlandId(sg, 13).orElseThrow().setLeasedToPlayer(true);
        drive(4);
        assertThat(incidents.findBySavegameOrderByIdAsc(sg)).isEmpty();
        ownership.findBySavegameAndFarmlandId(sg, 13).orElseThrow().setLeasedToPlayer(false);

        assertThat(cropDamage.damagedOwner(sg, 13)).contains(neighbor);
        assertThat(cropDamage.damagedOwner(sg, 12)).isEmpty(); // no owner in the tool
    }
}
