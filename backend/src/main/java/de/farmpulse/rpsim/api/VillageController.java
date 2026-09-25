package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.DiaryNoteRequest;
import de.farmpulse.rpsim.api.Requests.ProactiveRequest;
import de.farmpulse.rpsim.api.Views.CharacterDetailView;
import de.farmpulse.rpsim.api.Views.CharacterView;
import de.farmpulse.rpsim.api.Views.DiaryView;
import de.farmpulse.rpsim.api.Views.FarmlandShort;
import de.farmpulse.rpsim.api.Views.ProactiveView;
import de.farmpulse.rpsim.api.Views.ReputationView;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.communication.ConversationService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VillageController {

    private final SavegameContext context;
    private final CharacterRepository characters;
    private final CommunicationRepository communications;
    private final ConversationService conversations;
    private final FarmlandOwnershipService ownership;
    private final NegotiationEngine negotiations;
    private final DiaryService diary;
    private final VillageReputationService reputation;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public VillageController(SavegameContext context, CharacterRepository characters, CommunicationRepository communications,
                             ConversationService conversations, FarmlandOwnershipService ownership,
                             NegotiationEngine negotiations, DiaryService diary, VillageReputationService reputation,
                             ApiMapper mapper, RpsimProperties props) {
        this.context = context;
        this.characters = characters;
        this.communications = communications;
        this.conversations = conversations;
        this.ownership = ownership;
        this.negotiations = negotiations;
        this.diary = diary;
        this.reputation = reputation;
        this.mapper = mapper;
        this.props = props;
    }

    private List<FarmlandShort> fields(Savegame sg, Character c) {
        return ownership.ownedBy(c).stream().map(o -> new FarmlandShort(o.getFarmlandId(), o.getHectares(),
                o.getReferencePrice(), negotiations.isBlocked(sg, AssetType.FARMLAND, String.valueOf(o.getFarmlandId()))))
                .toList();
    }

    /** Villagers incl. former ones (applicants excluded); trust only as an abstract level. */
    @GetMapping("/api/characters")
    @Transactional(readOnly = true)
    public List<CharacterView> characters() {
        Savegame sg = context.requireActive();
        return characters.findBySavegameOrderByIdAsc(sg).stream()
                .filter(c -> c.getCategory() != CharacterCategory.APPLICANT)
                .map(c -> new CharacterView(c.getId(), c.getName(), c.getRole().name(), c.getCategory().name(),
                        c.getStatus().name(), mapper.trustLevel(c), c.getShortDescription(), fields(sg, c)))
                .toList();
    }

    @GetMapping("/api/characters/{id}")
    @Transactional(readOnly = true)
    public CharacterDetailView character(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        Character c = characters.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("character " + id));
        List<Communication> msgs = communications.findBySavegameAndCharacterOrderByIdDesc(sg, c);
        boolean pacing = c.getLastPacedMessageGameTime() != null && GameTime.toDays(sg.getCurrentGameTime()
                - c.getLastPacedMessageGameTime()) < props.getFormulas().getMessages().getPacingCooldownDays();
        return new CharacterDetailView(c.getId(), c.getName(), c.getRole().name(), c.getCategory().name(),
                c.getStatus().name(), mapper.trustLevel(c), c.getTraits(), c.getSpeechStyle(), c.getBackstory(),
                c.getShortDescription(), fields(sg, c), pacing, msgs.stream().anyMatch(Communication::isOpenTopic),
                msgs.stream().limit(10).map(mapper::message).toList());
    }

    /** "Nachricht verfassen" - free text; the pacing cooldown only suppresses trust effects. */
    @PostMapping("/api/characters/{id}/messages")
    @Transactional
    public ProactiveView message(@PathVariable Long id, @Valid @RequestBody ProactiveRequest r) {
        ConversationService.ProactiveResult res = conversations.proactive(context.requireActive(), id, r.text(), r.channel());
        return new ProactiveView(mapper.message(res.message()), res.pacingActive());
    }

    @GetMapping("/api/diary")
    @Transactional(readOnly = true)
    public List<DiaryView> diary() {
        return diary.list(context.requireActive()).stream().map(mapper::diary).toList();
    }

    /** Free player note - purely narrative, no mechanical effect. */
    @PostMapping("/api/diary/entries")
    @Transactional
    public DiaryView note(@Valid @RequestBody DiaryNoteRequest r) {
        return mapper.diary(diary.addPlayerNote(context.requireActive(), r.title(), r.text()));
    }

    /** Only the tier - never the raw score. */
    @GetMapping("/api/village-reputation")
    @Transactional(readOnly = true)
    public ReputationView reputation() {
        VillageReputationService.Tier t = reputation.tier(context.requireActive());
        return new ReputationView(t.name(), switch (t) {
            case GOOD -> "gut angesehen";
            case NEUTRAL -> "neutral";
            case CONTROVERSIAL -> "umstritten";
        });
    }
}
