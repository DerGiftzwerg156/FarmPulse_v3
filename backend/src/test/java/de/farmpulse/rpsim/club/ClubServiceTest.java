package de.farmpulse.rpsim.club;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V2 R2-E4: festival invitations with RSVP and sponsoring requests of the clubs. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ClubServiceTest {

    @Autowired Fixtures fx;
    @Autowired ClubService clubs;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired TrustEventRepository trustEvents;
    @Autowired VillageReputationService reputation;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Rohde");
        fx.snapshot(sg, 100_000);
    }

    private ServiceCase invitation() {
        // fallback calendar: day 99 = period 4 (June) -> Schützenfest of the shooting club
        sg.setCurrentGameTime(GameTime.days(99));
        assertThat(clubs.invite(sg)).isTrue();
        return cases.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == CaseKind.INVITATION)
                .findFirst().orElseThrow();
    }

    @Test
    void theHostOfTheFestivalInvitesAndAcceptingRaisesTrust() {
        ServiceCase inv = invitation();
        assertThat(inv.getReference()).isEqualTo("SCHUETZENFEST");
        Character host = inv.getCharacter();
        assertThat(host.getRole()).isEqualTo(CharacterRole.CLUB);
        assertThat(host.getAffiliation()).isEqualTo("SHOOTING_CLUB");
        assertThat(clubs.club(sg, "SHOOTING_CLUB").getId()).as("one chair per club").isEqualTo(host.getId());
        clubs.acceptInvitation(sg, inv.getId());
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(host))
                .anyMatch(e -> e.getReason() == TrustReason.INVITATION_ACCEPTED);
    }

    @Test
    void anIgnoredInvitationCostsALittleTrustADeclinedOneNothing() {
        ServiceCase inv = invitation();
        sg.setCurrentGameTime(inv.getDeadlineGameTime() + 1);
        clubs.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(inv.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(inv.getCharacter()))
                .anyMatch(e -> e.getReason() == TrustReason.INVITATION_IGNORED && e.getDelta() == -1);
    }

    @Test
    void aSponsoringInAFixedTierRaisesTheVillageReputation() {
        ServiceCase req = clubs.requestSponsoring(sg, "FIRE_BRIGADE");
        assertThatThrownBy(() -> clubs.sponsor(sg, req.getId(), 300)).isInstanceOf(BusinessRuleException.class);
        double before = reputation.publicActionSum(sg);
        clubs.sponsor(sg, req.getId(), 1000);
        assertThat(reputation.publicActionSum(sg) - before).isEqualTo(5.0);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("\"amount\":-1000")
                && o.getPayloadJson().contains("SPONSORING"));
        ServiceCase other = clubs.requestSponsoring(sg, "SPORTS_CLUB");
        clubs.declineSponsoring(sg, other.getId());
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(other.getCharacter()))
                .anyMatch(e -> e.getReason() == TrustReason.SPONSORING_DECLINED);
    }
}
