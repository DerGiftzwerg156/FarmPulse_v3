package de.farmpulse.rpsim.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.ChatGroup;
import de.farmpulse.rpsim.domain.ChatMessage;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.AiNarrationService;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.ChatMessageRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-D2: the village group chat "Dorfchat" (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VillageChatTest {

    @Autowired Fixtures fx;
    @Autowired VillageChatService chat;
    @Autowired VillageNewsService news;
    @Autowired AiNarrationService ai;
    @Autowired NarrationJobRepository jobs;
    @Autowired ChatMessageRepository messages;
    @Autowired ServiceCaseRepository cases;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character anna;
    Character ben;
    Character neighbor;
    Character chair;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        anna = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Anna Albers");
        ben = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Ben Brandt");
        neighbor = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Hauke Harms");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Frau Gerdes");
        fx.character(sg, CharacterRole.BANK_ADVISOR, CharacterCategory.MANDATORY, "Frau Berger");
        chair = fx.character(sg, CharacterRole.CLUB, CharacterCategory.MANDATORY, "Klaus Kröger");
        chair.setAffiliation("SHOOTING_CLUB");
    }

    private ChatGroup group(String key) {
        return chat.syncGroups(sg).stream().filter(g -> g.getGroupKey().equals(key)).findFirst().orElseThrow();
    }

    private List<String> names(ChatGroup g) {
        return chat.memberCharacters(g).stream().map(Character::getName).toList();
    }

    private void narrate() {
        for (NarrationJob j : jobs.findBySavegameOrderByIdAsc(sg)) {
            if (j.getStatus() == NarrationJobStatus.PENDING) {
                ai.process(j);
            }
        }
    }

    private void nextDay() {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1));
    }

    @Test
    void groupsVillageNeighboursAndClubsFollowTheVillage() {
        assertThat(chat.syncGroups(sg)).extracting(ChatGroup::getGroupKey)
                .containsExactly("DORF", "NACHBARN", "CLUB:SHOOTING_CLUB");
        assertThat(names(group("DORF"))).containsExactlyInAnyOrder("Anna Albers", "Ben Brandt", "Hauke Harms", "Frau Gerdes");
        assertThat(names(group("NACHBARN"))).containsExactly("Hauke Harms");
        assertThat(names(group("CLUB:SHOOTING_CLUB"))).containsExactlyInAnyOrder("Klaus Kröger", "Anna Albers", "Ben Brandt");
        assertThat(group("CLUB:SHOOTING_CLUB").getName()).isEqualTo("Schützenverein");

        anna.setStatus(CharacterStatus.TERMINATED);
        assertThat(names(group("DORF"))).doesNotContain("Anna Albers");
        assertThat(names(group("CLUB:SHOOTING_CLUB"))).doesNotContain("Anna Albers");
    }

    private ServiceCase request() {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.GOODS_REQUEST);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(neighbor);
        sc.setReference("WHEAT");
        sc.setQuantity(5000);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setCreatedAt(Instant.now());
        return cases.save(sc);
    }

    @Test
    void everyNewNeighbourRequestPostsOnceWithALinkAndTheDailyLimitHolds() {
        chat.syncGroups(sg);
        ServiceCase sc = request();

        List<ChatMessage> help = chat.helpRequests(sg);
        assertThat(help).hasSize(1);
        assertThat(help.getFirst().getLink()).isEqualTo("/handel?case=" + sc.getId());
        assertThat(help.getFirst().getCharacter().getId()).isEqualTo(neighbor.getId());
        assertThat(help.getFirst().getGroupId()).isEqualTo(group("NACHBARN").getId());
        assertThat(chat.helpRequests(sg)).isEmpty(); // once per request

        int max = props.getFormulas().getVillageChat().getMaxPostsPerDay();
        int posted = 1;
        while (chat.announce(sg).isPresent()) {
            posted++;
        }
        assertThat(posted).isEqualTo(max);
        assertThat(chat.gossip(sg)).isEmpty();

        narrate();
        assertThat(messages.findBySavegameOrderByIdAsc(sg)).allSatisfy(m -> {
            assertThat(m.isPending()).isFalse();
            assertThat(m.getText()).isNotBlank();
        });

        ServiceCase late = request();
        assertThat(chat.helpRequests(sg)).isEmpty(); // the limit of the day is reached ...
        nextDay();
        assertThat(chat.helpRequests(sg)).extracting(ChatMessage::getRelatedCaseId).containsExactly(late.getId()); // ... tomorrow
        assertThat(chat.gossip(sg)).isPresent(); // a new day, a new limit
    }

    @Test
    void playerPostsChangeTrustOfOneMemberOncePerDayAndAMemberAnswers() {
        ChatGroup dorf = group("DORF");
        List<Character> members = chat.memberCharacters(dorf);
        double before = members.stream().mapToDouble(Character::getTrustScore).sum();

        VillageChatService.PlayerPost p = chat.write(sg, dorf.getId(), "Vielen Dank euch allen, liebe Grüße!");
        assertThat(p.pacingActive()).isFalse();
        assertThat(p.message().getTone()).isEqualTo("FRIENDLY");
        double after = members.stream().mapToDouble(Character::getTrustScore).sum();
        assertThat(after).isGreaterThan(before);
        assertThat(after - before).isLessThanOrEqualTo(props.getFormulas().getTone().getCap());

        VillageChatService.PlayerPost again = chat.write(sg, dorf.getId(), "Danke nochmal, liebe Grüße!");
        assertThat(again.pacingActive()).isTrue();
        assertThat(members.stream().mapToDouble(Character::getTrustScore).sum()).isEqualTo(after);

        List<ChatMessage> list = chat.messages(sg, dorf.getId(), 100);
        assertThat(list).extracting(ChatMessage::getKind).containsExactly("PLAYER", "REPLY", "PLAYER", "REPLY");
        assertThat(jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "CHAT_REPLY")).hasSize(2)
                .allSatisfy(j -> assertThat(j.getPlayerMessage()).isNotBlank());
    }

    @Test
    void aRecordHarvestBringsCongratulationsInTheVillageGroup() {
        news.add(sg, VillageNewsService.Section.FARM, "RECORD_HARVEST", "Rekordernte: Hof meldet den besten Erntemonat.");
        List<ChatMessage> list = chat.messages(sg, group("DORF").getId(), 10);
        assertThat(list).extracting(ChatMessage::getKind).containsExactly("CONGRATULATION");
        assertThat(list.getFirst().getCharacter().getRole()).isEqualTo(CharacterRole.VILLAGER);
    }
}
