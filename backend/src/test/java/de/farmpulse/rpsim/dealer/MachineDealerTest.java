package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.api.MachineDealerController;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.onboarding.OnboardingService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-H1 (owner decisions 2026-10-15): the machinery dealer is part of the starting cast of a new
 * savegame; an older savegame gets the dealer, introduced by mail and diary, when the app "Händler" is opened for the
 * first time - and only once.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MachineDealerTest {

    @Autowired Fixtures fx;
    @Autowired MachineDealerController controller;
    @Autowired SavegameContext context;
    @Autowired CharacterRepository characters;
    @Autowired NarrationJobRepository jobs;
    @Autowired DiaryEntryRepository diary;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
    }

    private long dealers() {
        return characters.findBySavegameOrderByIdAsc(sg).stream()
                .filter(c -> c.getRole() == CharacterRole.MACHINE_DEALER).count();
    }

    @Test
    void theDealerBelongsToTheStartingCast() {
        assertThat(OnboardingService.MANDATORY_ROLES).contains(CharacterRole.MACHINE_DEALER);
    }

    @Test
    void anOlderSavegameGetsTheDealerWhenTheAppIsOpenedOnce() {
        // looking at the app without opening it creates nobody
        assertThat(controller.dealer().dealer()).isNull();
        assertThat(dealers()).isZero();

        var opened = controller.open().dealer();
        assertThat(opened.character().role()).isEqualTo("MACHINE_DEALER");
        assertThat(opened.trustLevel()).isNotBlank();
        assertThat(characters.findById(opened.character().id()).orElseThrow().getCategory())
                .isEqualTo(CharacterCategory.MANDATORY);
        // introduced like every service character: mail (narration job) and diary
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType)
                .containsExactly("CHARACTER_INTRODUCTION");
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d ->
                assertThat(d.getTitle()).isEqualTo(opened.character().name() + " stellt sich vor"));

        // opening again changes nothing
        assertThat(controller.open().dealer().character().id()).isEqualTo(opened.character().id());
        assertThat(controller.dealer().dealer().character().id()).isEqualTo(opened.character().id());
        assertThat(dealers()).isEqualTo(1);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).hasSize(1);
    }

    @Test
    void aDealerOfTheStartingCastIsShownWithoutAnIntroduction() {
        var dealer = fx.character(sg, CharacterRole.MACHINE_DEALER, CharacterCategory.MANDATORY, "Herr Hansen");
        assertThat(controller.open().dealer().character().id()).isEqualTo(dealer.getId());
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }
}
