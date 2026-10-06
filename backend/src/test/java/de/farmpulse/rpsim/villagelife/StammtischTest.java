package de.farmpulse.rpsim.villagelife;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-D3: the regulars' table (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class StammtischTest {

    @Autowired Fixtures fx;
    @Autowired StammtischService stammtisch;
    @Autowired ContractActions actions;
    @Autowired MarketEventEngine market;
    @Autowired ServiceCaseRepository cases;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired FarmlandOwnershipRepository ownership;

    Savegame sg;
    List<Character> villagers;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        villagers = List.of(fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Anna Albers"),
                fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Ben Brandt"),
                fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Clara Claußen"),
                fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "David Dierks"));
    }

    private void day() {
        stammtisch.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<ServiceCase> invitations() {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.STAMMTISCH_INVITATION));
    }

    private double loner() {
        return publicActions.findBySavegameOrderByGameTimeAsc(sg).stream()
                .filter(e -> e.getType() == PublicActionType.STAMMTISCH_LONER).mapToDouble(PublicActionEvent::getDelta).sum();
    }

    @Test
    void aVillagerInvitesEveryFourteenDays() {
        day();
        assertThat(invitations()).isEmpty();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(14));
        day();
        assertThat(invitations()).hasSize(1);
        ServiceCase sc = invitations().getFirst();
        assertThat(sc.getCharacter().getRole()).isEqualTo(CharacterRole.VILLAGER);
        assertThat(sc.getDeadlineGameTime() - sg.getCurrentGameTime()).isEqualTo(GameTime.days(2));
        day();
        assertThat(invitations()).hasSize(1); // the next one in 14 days
    }

    @Test
    void attendingBuildsTrustAndMakesTheNextRumourMoreReliable() {
        ServiceCase sc = stammtisch.invite(sg).orElseThrow();
        double before = villagers.stream().mapToDouble(Character::getTrustScore).sum();
        assertThat(market.rumorAccuracy(sg)).isEqualTo(0.7);

        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(villagers.stream().mapToDouble(Character::getTrustScore).sum() - before).isEqualTo(3 * 2.0);
        assertThat(sc.getCharacter().getTrustScore()).isGreaterThan(0); // the host is at the table
        assertThat(market.rumorAccuracy(sg)).isEqualTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(market.rumorAccuracy(sg)).isEqualTo(0.7); // used up by one rumour
        assertThatThrownBy(() -> actions.acceptCase(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void missingThreeInARowCostsReputationCappedAtThree() {
        for (int i = 0; i < 2; i++) {
            actions.declineCase(sg, stammtisch.invite(sg).orElseThrow().getId());
        }
        assertThat(loner()).isZero();
        ServiceCase ignored = stammtisch.invite(sg).orElseThrow();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(3));
        day(); // unanswered counts as missed
        assertThat(ignored.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(loner()).isEqualTo(-1);
        for (int i = 0; i < 12; i++) {
            actions.declineCase(sg, stammtisch.invite(sg).orElseThrow().getId());
        }
        assertThat(loner()).isEqualTo(-3);

        actions.declineCase(sg, stammtisch.invite(sg).orElseThrow().getId());
        actions.acceptCase(sg, stammtisch.invite(sg).orElseThrow().getId()); // attending restarts the row
        assertThat(sg.getStammtischMissed()).isZero();
    }

    @Test
    void theTipNamesASellWillingFieldOwner() {
        assertThat(stammtisch.tip(sg)).isEmpty();
        Character owner = villagers.getFirst();
        owner.setSellWilling(true);
        FarmlandOwnership o = new FarmlandOwnership();
        o.setSavegame(sg);
        o.setFarmlandId(13);
        o.setOwnerType(OwnerType.CHARACTER);
        o.setOwnerCharacter(owner);
        o.setReferencePrice(72_000);
        o.setHectares(6);
        ownership.save(o);
        assertThat(stammtisch.tip(sg)).contains("Anna Albers würde Feld 13 verkaufen.");
    }
}
