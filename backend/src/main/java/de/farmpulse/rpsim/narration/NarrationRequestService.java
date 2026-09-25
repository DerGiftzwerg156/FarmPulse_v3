package de.farmpulse.rpsim.narration;

import java.time.Instant;
import java.util.Optional;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.AbsenceVariant;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fact-layer side of the narration pipeline: services only create a {@link NarrationJob} holding finished
 * facts. The AI is called later by the narration worker ({@code AiNarrationService}), never from here.
 * Also applies the mandatory-role absence routing (delayed reply with absence note, or substitute).
 */
@Service
public class NarrationRequestService {

    private final NarrationJobRepository jobs;
    private final CharacterRepository characters;
    private final JsonMapper json;
    private final RpsimProperties props;

    public NarrationRequestService(NarrationJobRepository jobs, CharacterRepository characters, JsonMapper json,
                                   RpsimProperties props) {
        this.jobs = jobs;
        this.characters = characters;
        this.json = json;
        this.props = props;
    }

    /** Builder-style request. */
    public final class Request {
        private final Savegame savegame;
        private final NarrationEventType type;
        private Character character;
        private NarrationFacts facts = NarrationFacts.builder().build();
        private Channel channel = Channel.MAIL;
        private CommunicationCategory category = CommunicationCategory.GENERAL;
        private String playerMessage;
        private String relatedType;
        private Long relatedId;
        private Long threadRootId;
        private String formLink;
        private long delayMs;

        private Request(Savegame savegame, NarrationEventType type) {
            this.savegame = savegame;
            this.type = type;
        }

        public Request from(Character c) { this.character = c; return this; }
        public Request facts(NarrationFacts f) { this.facts = f; return this; }
        public Request channel(Channel c) { this.channel = c; return this; }
        public Request category(CommunicationCategory c) { this.category = c; return this; }
        public Request playerMessage(String m) { this.playerMessage = m; return this; }
        public Request related(String type, Long id) { this.relatedType = type; this.relatedId = id; return this; }
        public Request thread(Long rootId) { this.threadRootId = rootId; return this; }
        public Request formLink(String link) { this.formLink = link; return this; }
        public Request delay(long ms) { this.delayMs = ms; return this; }

        public NarrationJob submit() {
            return NarrationRequestService.this.submit(this);
        }
    }

    public Request request(Savegame sg, NarrationEventType type) {
        return new Request(sg, type);
    }

    @Transactional
    NarrationJob submit(Request r) {
        Character speaker = r.character;
        long notBefore = r.savegame.getCurrentGameTime() + r.delayMs;
        NarrationFacts facts = r.facts;
        if (speaker != null && speaker.getStatus() == CharacterStatus.ON_LEAVE) {
            if (speaker.getAbsenceVariant() == AbsenceVariant.SUBSTITUTE) {
                Optional<Character> substitute = substituteFor(speaker);
                if (substitute.isPresent()) {
                    speaker = substitute.get();
                }
            } else {
                // Delayed reply: fixed extra delay + automatic absence note, no new character.
                notBefore += GameTime.hours(props.getFormulas().getAbsence().getDelayHours());
                NarrationFacts.Builder b = NarrationFacts.builder();
                facts.asMap().forEach(b::put);
                b.put("absenceNote", true);
                facts = b.build();
            }
        }
        NarrationJob job = new NarrationJob();
        job.setSavegame(r.savegame);
        job.setCharacter(speaker);
        job.setEventType(r.type.name());
        job.setChannel(r.channel);
        job.setCategory(r.category);
        job.setFactsJson(json.writeValueAsString(facts.asMap()));
        job.setPlayerMessage(r.playerMessage);
        job.setStatus(NarrationJobStatus.PENDING);
        job.setNotBeforeGameTime(notBefore);
        job.setRelatedEntityType(r.relatedType);
        job.setRelatedEntityId(r.relatedId);
        job.setThreadRootId(r.threadRootId);
        job.setFormLink(r.formLink);
        job.setCreatedAt(Instant.now());
        return jobs.save(job);
    }

    private Optional<Character> substituteFor(Character original) {
        return characters.findBySavegameAndStatus(original.getSavegame(), CharacterStatus.ACTIVE).stream()
                .filter(c -> original.getId().equals(c.getSubstituteForId()))
                .findFirst();
    }
}
