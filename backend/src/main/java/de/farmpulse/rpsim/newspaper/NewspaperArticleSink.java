package de.farmpulse.rpsim.newspaper;

import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.narration.NarrationSink;
import de.farmpulse.rpsim.narration.PromptBuilder;
import de.farmpulse.rpsim.repository.NewspaperArticleRepository;
import de.farmpulse.rpsim.repository.NewspaperIssueRepository;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.1 R31-D1: stores the narrated headline and text of a newspaper article. The first article of an issue
 * gives the issue its headline, which also goes into the diary and thus into the farm chronicle (R3-T2).
 */
@Component
public class NewspaperArticleSink implements NarrationSink {

    private final NewspaperArticleRepository articles;
    private final NewspaperIssueRepository issues;
    private final DiaryService diary;

    public NewspaperArticleSink(NewspaperArticleRepository articles, NewspaperIssueRepository issues, DiaryService diary) {
        this.articles = articles;
        this.issues = issues;
        this.diary = diary;
    }

    @Override
    public String targetType() {
        return PromptBuilder.TARGET_NEWSPAPER;
    }

    @Override
    public void deliver(NarrationJob job, String subject, String body, boolean fallback) {
        articles.findById(job.getTargetId()).ifPresent(a -> {
            String headline = subject == null ? "" : subject.strip();
            a.setHeadline(headline.length() > 255 ? headline.substring(0, 255) : headline);
            a.setBody(body);
            a.setFallback(fallback);
            issues.findById(a.getIssueId()).filter(i -> i.getHeadline() == null).ifPresent(i -> {
                i.setHeadline(a.getHeadline());
                diary.addAuto(job.getSavegame(), "ROTATION", "Dorfblatt Nr. " + i.getIssueNumber() + ": " + a.getHeadline(),
                        "Neue Ausgabe des Dorfblatts.", VillageNewspaperService.RELATED, i.getId());
            });
        });
    }
}
