package de.farmpulse.rpsim.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.club.ClubService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.ChatGroup;
import de.farmpulse.rpsim.domain.ChatMember;
import de.farmpulse.rpsim.domain.ChatMessage;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.narration.PromptBuilder;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.ChatGroupRepository;
import de.farmpulse.rpsim.repository.ChatMemberRepository;
import de.farmpulse.rpsim.repository.ChatMessageRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.tone.ToneClassifier;
import de.farmpulse.rpsim.tone.ToneTrustService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D2 (owner decisions 2026-10-05): the village group chat "Dorfchat".
 * <ul>
 *   <li>Groups: "Dorf" (active villagers, neighbours and the cooperative), "Nachbarn" (the neighbour farmers) and one
 *   group per club whose chair is in the village (chair + a few random villagers). Members follow the village daily.</li>
 *   <li>Characters: with {@code daily-post-probability} an announcement (topics of the config) or gossip; congratulations
 *   on a record harvest and announcements of festivals (news items of the newspaper); every new request of a neighbour
 *   (goods R3-H4, field work R3-H5, animals A3) posts a help request with a link. At most {@code max-posts-per-day}
 *   character posts per game day; a request that does not fit any more posts the next day while it is open.</li>
 *   <li>The player writes like "Nachricht verfassen": tone classifier, a capped trust change for ONE random member at
 *   most once per group within {@code pacing-days}, one member answers by AI; no mechanics through free text.</li>
 * </ul>
 * Texts are written by narration jobs with target {@code CHAT_MESSAGE} (template without AI).
 */
@Service
public class VillageChatService {

    public static final String DORF = "DORF";
    public static final String NACHBARN = "NACHBARN";
    public static final String CLUB_PREFIX = "CLUB:";

    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String GOSSIP = "GOSSIP";
    public static final String CONGRATULATION = "CONGRATULATION";
    public static final String HELP_REQUEST = "HELP_REQUEST";
    public static final String REPLY = "REPLY";
    public static final String PLAYER = "PLAYER";

    static final Set<CaseKind> HELP_KINDS = EnumSet.of(CaseKind.GOODS_REQUEST, CaseKind.NEIGHBOR_MISSION,
            CaseKind.ANIMAL_REQUEST);
    static final int MAX_TEXT = 1000;

    private final ChatGroupRepository groups;
    private final ChatMemberRepository members;
    private final ChatMessageRepository messages;
    private final CharacterRepository characters;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final NarrationRequestService narration;
    private final ToneClassifier classifier;
    private final ToneTrustService toneTrust;
    private final RandomSource random;
    private final RpsimProperties props;

    public VillageChatService(ChatGroupRepository groups, ChatMemberRepository members, ChatMessageRepository messages,
                              CharacterRepository characters, ServiceCaseRepository cases, SavegameRepository savegames,
                              NarrationRequestService narration, ToneClassifier classifier, ToneTrustService toneTrust,
                              RandomSource random, RpsimProperties props) {
        this.groups = groups;
        this.members = members;
        this.messages = messages;
        this.characters = characters;
        this.cases = cases;
        this.savegames = savegames;
        this.narration = narration;
        this.classifier = classifier;
        this.toneTrust = toneTrust;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.VillageChat cfg() {
        return props.getFormulas().getVillageChat();
    }

    // ------------------------------------------------------------------------------------------ groups

    /** Creates the groups and follows the members (arrivals join, departed characters leave). */
    @Transactional
    public List<ChatGroup> syncGroups(Savegame sg) {
        List<Character> active = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE);
        List<Character> village = active.stream().filter(c -> c.getRole() == CharacterRole.VILLAGER
                || c.getRole() == CharacterRole.NEIGHBOR_FARMER || c.getRole() == CharacterRole.COOPERATIVE).toList();
        sync(sg, group(sg, DORF, "Dorf"), village, false);
        sync(sg, group(sg, NACHBARN, "Nachbarn"), active.stream()
                .filter(c -> c.getRole() == CharacterRole.NEIGHBOR_FARMER).toList(), false);
        List<Character> villagers = active.stream().filter(c -> c.getRole() == CharacterRole.VILLAGER).toList();
        for (Character chair : active.stream().filter(c -> c.getRole() == CharacterRole.CLUB && c.getAffiliation() != null)
                .toList()) {
            ChatGroup g = group(sg, CLUB_PREFIX + chair.getAffiliation(), ClubService.label(chair.getAffiliation()));
            List<Character> current = memberCharacters(g);
            List<Character> want = new ArrayList<>(current.stream().filter(c -> c.getStatus() == CharacterStatus.ACTIVE).toList());
            if (want.stream().noneMatch(c -> c.getId().equals(chair.getId()))) {
                want.add(chair);
            }
            List<Character> candidates = new ArrayList<>(villagers.stream()
                    .filter(v -> want.stream().noneMatch(w -> w.getId().equals(v.getId()))).toList());
            long others = want.stream().filter(c -> c.getRole() == CharacterRole.VILLAGER).count();
            while (others < cfg().getClubGroupVillagers() && !candidates.isEmpty()) {
                Character pick = random.pick(candidates);
                candidates.remove(pick);
                want.add(pick);
                others++;
            }
            sync(sg, g, want, false);
        }
        return groups.findBySavegameOrderByIdAsc(sg);
    }

    private ChatGroup group(Savegame sg, String key, String name) {
        return groups.findBySavegameAndGroupKey(sg, key).orElseGet(() -> {
            ChatGroup g = new ChatGroup();
            g.setSavegame(sg);
            g.setGroupKey(key);
            g.setName(name);
            return groups.save(g);
        });
    }

    private void sync(Savegame sg, ChatGroup g, List<Character> want, boolean keepOthers) {
        Set<Long> wanted = new HashSet<>();
        want.forEach(c -> wanted.add(c.getId()));
        Set<Long> present = new HashSet<>();
        for (ChatMember m : members.findByGroupIdOrderByIdAsc(g.getId())) {
            if (!keepOthers && (!wanted.contains(m.getCharacter().getId())
                    || m.getCharacter().getStatus() == CharacterStatus.TERMINATED)) {
                members.delete(m);
            } else {
                present.add(m.getCharacter().getId());
            }
        }
        for (Character c : want) {
            if (!present.contains(c.getId())) {
                ChatMember m = new ChatMember();
                m.setSavegame(sg);
                m.setGroupId(g.getId());
                m.setCharacter(c);
                members.save(m);
                present.add(c.getId());
            }
        }
    }

    public List<Character> memberCharacters(ChatGroup g) {
        return members.findByGroupIdOrderByIdAsc(g.getId()).stream().map(ChatMember::getCharacter).toList();
    }

    private List<Character> activeMembers(ChatGroup g) {
        return memberCharacters(g).stream().filter(c -> c.getStatus() == CharacterStatus.ACTIVE).toList();
    }

    // ------------------------------------------------------------------------------------------ daily

    @EventListener
    @Order(62)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled()) {
            return;
        }
        syncGroups(sg);
        helpRequests(sg);
        if (random.chance(cfg().getDailyPostProbability())) {
            if (random.chance(0.5)) {
                announce(sg);
            } else {
                gossip(sg);
            }
        }
    }

    /** Character posts of the current game day (help requests included). */
    int postsToday(Savegame sg) {
        long day = GameTime.dayIndex(sg.getCurrentGameTime());
        if (sg.getChatPostsDay() == null || sg.getChatPostsDay() != day) {
            sg.setChatPostsDay(day);
            sg.setChatPostsCount(0);
        }
        return sg.getChatPostsCount();
    }

    boolean mayPost(Savegame sg) {
        return postsToday(sg) < cfg().getMaxPostsPerDay();
    }

    /** An announcement of a configured topic; the fire brigade drill goes to the club group when it exists. */
    @Transactional
    public Optional<ChatMessage> announce(Savegame sg) {
        if (!mayPost(sg) || cfg().getTopics().isEmpty()) {
            return Optional.empty();
        }
        String topic = random.pick(cfg().getTopics());
        ChatGroup g = "FIRE_BRIGADE_DRILL".equals(topic)
                ? groups.findBySavegameAndGroupKey(sg, CLUB_PREFIX + "FIRE_BRIGADE").orElse(null) : null;
        if (g == null) {
            g = groups.findBySavegameAndGroupKey(sg, DORF).orElse(null);
        }
        if (g == null) {
            return Optional.empty();
        }
        List<Character> from = activeMembers(g);
        if (from.isEmpty()) {
            return Optional.empty();
        }
        Character speaker = from.stream().filter(c -> c.getRole() == CharacterRole.CLUB).findFirst()
                .orElseGet(() -> random.pick(from));
        return Optional.of(post(sg, g, speaker, ANNOUNCEMENT, topic, NarrationEventType.CHAT_ANNOUNCEMENT,
                NarrationFacts.builder().put("topic", topic), null, null));
    }

    /** Harmless gossip about another member of the village group. */
    @Transactional
    public Optional<ChatMessage> gossip(Savegame sg) {
        if (!mayPost(sg)) {
            return Optional.empty();
        }
        ChatGroup g = groups.findBySavegameAndGroupKey(sg, DORF).orElse(null);
        if (g == null) {
            return Optional.empty();
        }
        List<Character> from = activeMembers(g);
        if (from.size() < 2) {
            return Optional.empty();
        }
        Character teller = random.pick(from);
        Character about = random.pick(from.stream().filter(c -> !c.getId().equals(teller.getId())).toList());
        return Optional.of(post(sg, g, teller, GOSSIP, null, NarrationEventType.CHAT_GOSSIP,
                NarrationFacts.builder().put("aboutName", about.getName()), null, null));
    }

    /** Every open request of a neighbour that was not posted yet goes into the group "Nachbarn" with its link. */
    @Transactional
    public List<ChatMessage> helpRequests(Savegame sg) {
        List<ChatMessage> out = new ArrayList<>();
        ChatGroup g = groups.findBySavegameAndGroupKey(sg, NACHBARN).orElse(null);
        if (g == null) {
            return out;
        }
        List<ServiceCase> open = new ArrayList<>(cases.findBySavegameAndKindInOrderByIdDesc(sg, HELP_KINDS));
        open.sort(Comparator.comparing(ServiceCase::getId));
        for (ServiceCase sc : open) {
            if (!mayPost(sg)) {
                break; // the daily limit: an open request posts the next day
            }
            if (sc.getStatus() != CaseStatus.AWAITING_PLAYER || sc.getCharacter() == null
                    || sc.getCharacter().getStatus() != CharacterStatus.ACTIVE
                    || messages.existsBySavegameAndRelatedCaseId(sg, sc.getId())) {
                continue;
            }
            NarrationFacts.Builder f = NarrationFacts.builder().put("requestKind", sc.getKind());
            if (sc.getKind() == CaseKind.NEIGHBOR_MISSION) {
                f.put("work", sc.getReference());
            } else {
                f.put("what", sc.getReference()).put("quantity", sc.getQuantity());
            }
            out.add(post(sg, g, sc.getCharacter(), HELP_REQUEST, sc.getKind().name(), NarrationEventType.CHAT_HELP_REQUEST, f,
                    "/handel?case=" + sc.getId(), sc.getId()));
        }
        return out;
    }

    /** Festivals and a record harvest from the newspaper news (D1): an announcement / congratulations in "Dorf". */
    @EventListener
    @Transactional
    public void onNews(VillageNewsService.VillageNewsAdded e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        String kind = e.kind();
        if (!"FESTIVAL".equals(kind) && !"RECORD_HARVEST".equals(kind) && !"DIESEL_THEFT".equals(kind)) {
            return;
        }
        syncGroups(sg);
        ChatGroup g = groups.findBySavegameAndGroupKey(sg, DORF).orElse(null);
        if (g == null || !mayPost(sg)) {
            return;
        }
        List<Character> from = activeMembers(g).stream().filter(c -> c.getRole() == CharacterRole.VILLAGER).toList();
        if (from.isEmpty()) {
            from = activeMembers(g);
        }
        if (from.isEmpty()) {
            return;
        }
        Character speaker = random.pick(from);
        switch (kind) {
            case "FESTIVAL" -> post(sg, g, speaker, ANNOUNCEMENT, "FESTIVAL", NarrationEventType.CHAT_ANNOUNCEMENT,
                    NarrationFacts.builder().put("topic", "FESTIVAL"), null, null);
            case "RECORD_HARVEST" -> post(sg, g, speaker, CONGRATULATION, kind, NarrationEventType.CHAT_CONGRATULATION,
                    NarrationFacts.builder().put("occasion", kind), null, null);
            default -> post(sg, g, speaker, GOSSIP, kind, NarrationEventType.CHAT_GOSSIP,
                    NarrationFacts.builder().put("topic", kind), null, null);
        }
    }

    private ChatMessage post(Savegame sg, ChatGroup g, Character from, String kind, String topic, NarrationEventType type,
                             NarrationFacts.Builder facts, String link, Long caseId) {
        postsToday(sg);
        sg.setChatPostsCount(sg.getChatPostsCount() + 1);
        ChatMessage m = new ChatMessage();
        m.setSavegame(sg);
        m.setGroupId(g.getId());
        m.setCharacter(from);
        m.setKind(kind);
        m.setTopic(topic);
        m.setLink(link);
        m.setRelatedCaseId(caseId);
        m.setGameTime(sg.getCurrentGameTime());
        m.setPending(true);
        messages.save(m);
        narration.request(sg, type).from(from).facts(facts.put("group", g.getName()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).target(PromptBuilder.TARGET_CHAT, m.getId()).submit();
        return m;
    }

    // ------------------------------------------------------------------------------------------ player

    public record PlayerPost(ChatMessage message, boolean pacingActive) {
    }

    /** A post of the player: tone, capped trust of one random member (pacing per group), one member answers. */
    @Transactional
    public PlayerPost write(Savegame sg, Long groupId, String text) {
        ChatGroup g = own(sg, groupId);
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new BusinessRuleException("EMPTY_MESSAGE", "Bitte eine Nachricht eingeben.");
        }
        if (t.length() > MAX_TEXT) {
            t = t.substring(0, MAX_TEXT);
        }
        long now = sg.getCurrentGameTime();
        ToneClass tone = classifier.classify(t).tone();
        ChatMessage m = new ChatMessage();
        m.setSavegame(sg);
        m.setGroupId(g.getId());
        m.setKind(PLAYER);
        m.setText(t);
        m.setTone(tone.name());
        m.setGameTime(now);
        messages.save(m);
        List<Character> present = activeMembers(g);
        boolean pacing = g.getLastPlayerPostGameTime() != null
                && GameTime.toDays(now - g.getLastPlayerPostGameTime()) < cfg().getPacingDays();
        if (!present.isEmpty()) {
            if (!pacing) {
                toneTrust.apply(random.pick(present), tone);
                g.setLastPlayerPostGameTime(now);
            }
            Character answer = random.pick(present);
            ChatMessage reply = new ChatMessage();
            reply.setSavegame(sg);
            reply.setGroupId(g.getId());
            reply.setCharacter(answer);
            reply.setKind(REPLY);
            reply.setGameTime(now);
            reply.setPending(true);
            messages.save(reply);
            narration.request(sg, NarrationEventType.CHAT_REPLY).from(answer).playerMessage(t)
                    .facts(NarrationFacts.builder().put("group", g.getName()).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).target(PromptBuilder.TARGET_CHAT, reply.getId()).submit();
        }
        return new PlayerPost(m, pacing);
    }

    private ChatGroup own(Savegame sg, Long id) {
        return groups.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("chat group " + id));
    }

    // ------------------------------------------------------------------------------------------ queries

    public record GroupInfo(ChatGroup group, List<Character> members, ChatMessage last) {
    }

    @Transactional
    public List<GroupInfo> groups(Savegame sg) {
        if (cfg().isEnabled()) {
            syncGroups(sg);
        }
        Map<Long, GroupInfo> out = new LinkedHashMap<>();
        for (ChatGroup g : groups.findBySavegameOrderByIdAsc(sg)) {
            List<ChatMessage> last = messages.findByGroupIdOrderByIdDesc(g.getId(), PageRequest.of(0, 1));
            out.put(g.getId(), new GroupInfo(g, activeMembers(g), last.isEmpty() ? null : last.getFirst()));
        }
        return List.copyOf(out.values());
    }

    /** The newest messages of a group, oldest first. */
    public List<ChatMessage> messages(Savegame sg, Long groupId, int limit) {
        ChatGroup g = own(sg, groupId);
        List<ChatMessage> list = new ArrayList<>(messages.findByGroupIdOrderByIdDesc(g.getId(),
                PageRequest.of(0, Math.max(1, Math.min(limit, 200)))));
        java.util.Collections.reverse(list);
        return list;
    }
}
