package de.farmpulse.rpsim.newspaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.NewspaperArticle;
import de.farmpulse.rpsim.domain.NewspaperIssue;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.AiNarrationService;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.PublicActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-D1: the village newspaper "Dorfblatt" (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VillageNewspaperTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired VillageNewspaperService newspaper;
    @Autowired VillageNewsService news;
    @Autowired PublicActionService publicActions;
    @Autowired AiNarrationService ai;
    @Autowired NarrationJobRepository jobs;
    @Autowired DiaryEntryRepository diary;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setFarmName("Hof Lindenau");
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(2);
        fx.snapshot(sg, 100_000);
    }

    private void nextMonth() {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
    }

    private void month() {
        newspaper.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void narrate() {
        for (NarrationJob j : jobs.findBySavegameOrderByIdAsc(sg)) {
            if (j.getStatus() == NarrationJobStatus.PENDING) {
                ai.process(j);
            }
        }
    }

    @Test
    void anIssueAtThePeriodStartPrintsThePublicFactsPerSection() {
        news.add(sg, VillageNewsService.Section.VILLAGE, "ARRIVAL", "Greta Lüders zieht neu ins Dorf (Dorfbewohner/in).");
        publicActions.record(sg, PublicActionType.SPONSORING, 1, "Sponsoring SHOOTING_CLUB 500 €");
        publicActions.record(sg, PublicActionType.AUTHORITY_FINE, -2, "Bußgeld 800 €");
        nextMonth();
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000).replace("230", "276");
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, facts);
        month();

        List<NewspaperIssue> issues = newspaper.list(sg);
        assertThat(issues).hasSize(1);
        NewspaperIssue issue = issues.getFirst();
        assertThat(issue.getIssueNumber()).isEqualTo(1);
        List<NewspaperArticle> articles = newspaper.articles(issue);
        assertThat(articles).extracting(NewspaperArticle::getSection)
                .containsExactly("VILLAGE", "FARM", "MARKET", "OFFICIAL"); // classifieds empty: left out
        assertThat(articles.get(2).getFactsJson()).contains("Weizen legt zu: +20 %");
        assertThat(articles.get(1).getFactsJson()).contains("Hof Lindenau unterstützt die Vereine im Dorf")
                .doesNotContain("€"); // never private money matters
        assertThat(articles.get(3).getFactsJson()).contains("Bußgeld").doesNotContain("800");

        narrate();
        assertThat(articles).allSatisfy(a -> {
            assertThat(a.getHeadline()).isNotBlank();
            assertThat(a.getBody()).isNotBlank();
            assertThat(a.isFallback()).isTrue();
        });
        assertThat(issue.getHeadline()).isEqualTo(articles.getFirst().getHeadline());
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).extracting(DiaryEntry::getTitle)
                .anyMatch(t -> t.startsWith("Dorfblatt Nr. 1: "));
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).allSatisfy(j -> assertThat(j.getCommunicationId()).isNull());

        month(); // once per period
        assertThat(newspaper.list(sg)).hasSize(1);
    }

    @Test
    void theNextIssueOnlyCarriesNewFactsAndNothingNewMeansNoIssue() {
        news.add(sg, VillageNewsService.Section.VILLAGE, "ARRIVAL", "Greta Lüders zieht neu ins Dorf.");
        nextMonth();
        month();
        assertThat(newspaper.list(sg)).hasSize(1);

        nextMonth();
        month();
        assertThat(newspaper.list(sg)).hasSize(1); // same prices, no news: no issue

        news.add(sg, VillageNewsService.Section.VILLAGE, "DEPARTURE", "Uwe Harms zieht aus dem Dorf weg.");
        nextMonth();
        month();
        List<NewspaperIssue> issues = newspaper.list(sg);
        assertThat(issues).hasSize(2);
        assertThat(issues.getFirst().getIssueNumber()).isEqualTo(2);
        assertThat(newspaper.articles(issues.getFirst()).getFirst().getFactsJson()).contains("Uwe Harms")
                .doesNotContain("Greta");
    }
}
