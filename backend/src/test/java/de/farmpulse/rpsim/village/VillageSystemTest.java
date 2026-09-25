package de.farmpulse.rpsim.village;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterGeneratorService.Spec;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.AbsenceVariant;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VillageSystemTest {

    @Autowired Fixtures fx;
    @Autowired VillageReputationService reputation;
    @Autowired VillageRotationService rotation;
    @Autowired MandatoryRoleAbsenceService absence;
    @Autowired PublicActionService publicActions;
    @Autowired CharacterGeneratorService generator;
    @Autowired TrustScoreService trust;
    @Autowired NarrationRequestService narration;
    @Autowired CharacterLookup lookup;
    @Autowired CharacterRepository characters;
    @Autowired LoanService loans;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        for (int i = 0; i < 5; i++) {
            generator.generate(sg, Spec.of(CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, 0), 100 + i);
        }
    }

    @Test
    void generatorIsDeterministicPerSeed() {
        Savegame other = fx.savegame();
        Character a = generator.generate(other, Spec.of(CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, 0), 777);
        Savegame third = fx.savegame();
        Character b = generator.generate(third, Spec.of(CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, 0), 777);
        assertThat(a.getName()).isEqualTo(b.getName());
        assertThat(a.getTraits()).isEqualTo(b.getTraits());
        assertThat(a.getTraits().split(", ")).hasSizeBetween(3, 5);
        assertThat(a.getShortDescription()).contains(a.getName());
    }

    @Test
    void reputationCombinesTrustAndDecayingPublicActions() {
        Character c = lookup.activeDynamic(sg).get(0);
        trust.recordEvent(c, 100, de.farmpulse.rpsim.domain.TrustReason.PROMISE_KEPT, null);
        // 5 villagers, one at 100 -> average 20 -> 0.6 * 20 = 12 -> neutral
        assertThat(reputation.trustAverage(sg)).isCloseTo(20, within(1e-9));
        assertThat(reputation.tier(sg)).isEqualTo(VillageReputationService.Tier.NEUTRAL);
        publicActions.record(sg, PublicActionType.VILLAGE_EVENT, 40, "Dorffest gesponsert");
        assertThat(reputation.tier(sg)).isEqualTo(VillageReputationService.Tier.GOOD); // 12 + 16 = 28
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(60)); // one half-life
        assertThat(reputation.publicActionSum(sg)).isCloseTo(20, within(1e-9));
        publicActions.record(sg, PublicActionType.PUBLIC_DEFAULT, -200, "Fälligstellung");
        assertThat(reputation.tier(sg)).isEqualTo(VillageReputationService.Tier.CONTROVERSIAL);
        assertThat(reputation.baseTrustForNewCharacter(sg)).isBetween(-15.0, 0.0);
    }

    /** AP-9.1: applicants and substitutes are not part of the village - their trust never moves the average. */
    @Test
    void applicantsAndSubstitutesDoNotCountForTheVillage() {
        double before = reputation.trustAverage(sg);
        Character applicant = fx.character(sg, de.farmpulse.rpsim.domain.CharacterRole.APPLICANT,
                de.farmpulse.rpsim.domain.CharacterCategory.APPLICANT, "Bewerberin");
        Character substitute = fx.character(sg, de.farmpulse.rpsim.domain.CharacterRole.BANK_ADVISOR,
                de.farmpulse.rpsim.domain.CharacterCategory.SUBSTITUTE, "Vertretung");
        trust.recordEvent(applicant, 100, de.farmpulse.rpsim.domain.TrustReason.PROMISE_KEPT, null);
        trust.recordEvent(substitute, 100, de.farmpulse.rpsim.domain.TrustReason.PROMISE_KEPT, null);
        assertThat(reputation.villagers(sg)).doesNotContain(applicant, substitute);
        assertThat(reputation.trustAverage(sg)).isCloseTo(before, within(1e-9));
    }

    @Test
    void rotationBudgetIsNeverExceededAndCountsArrivalsAndDeparturesTogether() {
        int changes = 0;
        for (int i = 0; i < 20; i++) {
            if (rotation.rotate(sg).isPresent()) {
                changes++;
            }
        }
        assertThat(changes).isEqualTo(2);
        assertThat(sg.getDynamicRotationsThisYear()).isEqualTo(2);
        // next (fallback) game year: 12 months * 1 day
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(12));
        rotation.resetBudgetOnYearChange(sg);
        assertThat(rotation.budgetLeft(sg)).isTrue();
    }

    @Test
    void departureAndArrival() {
        Character leaving = lookup.activeDynamic(sg).get(0);
        rotation.depart(sg, leaving);
        assertThat(leaving.getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        assertThat(leaving.getTerminationReason()).isIn(TerminationReason.MOVED_AWAY, TerminationReason.RETIREMENT);
        publicActions.record(sg, PublicActionType.VILLAGE_EVENT, 100, "Held");
        Character newcomer = rotation.arrive(sg);
        assertThat(newcomer.getCategory()).isEqualTo(CharacterCategory.DYNAMIC);
        assertThat(trust.getCurrentTrust(newcomer)).isCloseTo(reputation.baseTrustForNewCharacter(sg), within(0.5));
        assertThat(trust.getCurrentTrust(newcomer)).isPositive();
    }

    @Test
    void mandatoryAndEmployeesAreNeverRotated() {
        Character bank = fx.bank(sg);
        bank.setCategory(CharacterCategory.MANDATORY);
        for (int i = 0; i < 10; i++) {
            sg.setDynamicRotationsThisYear(0);
            rotation.rotate(sg);
        }
        assertThat(bank.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
    }

    @Test
    void absenceWithSubstituteRoutesMessagesAndRestoresOriginal() {
        Character bank = fx.bank(sg);
        loans.create(sg, 10_000, 0.05, 12, "Kredit", true, null);
        absence.startAbsence(sg, bank, AbsenceVariant.SUBSTITUTE);
        assertThat(bank.getStatus()).isEqualTo(CharacterStatus.ON_LEAVE);
        Character sub = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .filter(c -> bank.getId().equals(c.getSubstituteForId())).findFirst().orElseThrow();
        assertThat(sub.getRole()).isEqualTo(bank.getRole());
        NarrationJob job = narration.request(sg, NarrationEventType.CREDIT_APPROVED).from(bank)
                .facts(NarrationFacts.builder().build()).submit();
        assertThat(job.getCharacter().getId()).isEqualTo(sub.getId());
        // fact file stays with the role: the bank lookup still returns the original holder, loans untouched
        assertThat(lookup.bank(sg).orElseThrow().getId()).isEqualTo(bank.getId());
        assertThat(loans.list(sg)).hasSize(1);
        sg.setCurrentGameTime(bank.getOnLeaveUntilGameTime());
        absence.endAbsences(sg);
        assertThat(bank.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        assertThat(sub.getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        assertThat(sub.getTerminationReason()).isEqualTo(TerminationReason.SUBSTITUTE_ENDED);
    }

    @Test
    void absenceWithDelayedReplyAddsDelayAndNote() {
        Character bank = fx.bank(sg);
        absence.startAbsence(sg, bank, AbsenceVariant.DELAYED_REPLY);
        NarrationJob job = narration.request(sg, NarrationEventType.CREDIT_APPROVED).from(bank)
                .facts(NarrationFacts.builder().put("decision", "APPROVED").build()).submit();
        assertThat(job.getCharacter().getId()).isEqualTo(bank.getId());
        assertThat(job.getNotBeforeGameTime()).isEqualTo(sg.getCurrentGameTime() + GameTime.hours(24));
        assertThat(job.getFactsJson()).contains("absenceNote");
    }

    @Test
    void roleChangeKeepsFactFileButRestartsRelationship() {
        Character bank = fx.bank(sg);
        bank.setCategory(CharacterCategory.MANDATORY);
        trust.recordEvent(bank, 60, de.farmpulse.rpsim.domain.TrustReason.PROMISE_KEPT, null);
        loans.create(sg, 10_000, 0.05, 12, "Kredit", true, null);
        Character successor = rotation.replaceMandatory(sg, bank);
        assertThat(lookup.bank(sg).orElseThrow().getId()).isEqualTo(successor.getId());
        assertThat(loans.list(sg)).hasSize(1);
        assertThat(trust.getCurrentTrust(successor)).isLessThan(60);
    }
}
