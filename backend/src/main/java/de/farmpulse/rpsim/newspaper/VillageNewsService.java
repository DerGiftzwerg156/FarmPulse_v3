package de.farmpulse.rpsim.newspaper;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VillageNews;
import de.farmpulse.rpsim.repository.VillageNewsRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D1: services drop public events here (arrivals, festivals, record harvest, gossip about night work,
 * diesel theft ...); the next issue of the "Dorfblatt" prints them in their section. Never private money matters.
 */
@Service
public class VillageNewsService {

    /** Sections of the newspaper in print order with their German title. */
    public enum Section {
        VILLAGE("Aus dem Dorf"),
        FARM("Vom Hof"),
        MARKET("Markt"),
        OFFICIAL("Amtliches"),
        CLASSIFIEDS("Kleinanzeigen");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Published after a news item was stored (the village chat reacts to festivals and a record harvest). */
    public record VillageNewsAdded(Long savegameId, Long newsId, String kind) {
    }

    private final VillageNewsRepository news;
    private final ApplicationEventPublisher events;

    public VillageNewsService(VillageNewsRepository news, ApplicationEventPublisher events) {
        this.news = news;
        this.events = events;
    }

    @Transactional
    public VillageNews add(Savegame sg, Section section, String kind, String text) {
        VillageNews n = new VillageNews();
        n.setSavegame(sg);
        n.setSection(section.name());
        n.setKind(kind);
        n.setText(text.length() > 500 ? text.substring(0, 500) : text);
        n.setGameTime(sg.getCurrentGameTime());
        news.save(n);
        events.publishEvent(new VillageNewsAdded(sg.getId(), n.getId(), kind));
        return n;
    }
}
