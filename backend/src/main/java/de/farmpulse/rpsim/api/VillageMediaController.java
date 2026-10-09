package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.CharacterRef;
import de.farmpulse.rpsim.chat.VillageChatService;
import de.farmpulse.rpsim.domain.ChatMessage;
import de.farmpulse.rpsim.domain.NewspaperArticle;
import de.farmpulse.rpsim.domain.NewspaperIssue;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.newspaper.VillageNewspaperService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Roadmap V3.1 R31-D1 / R31-D2: the apps "Dorfblatt" (newspaper issues) and "Dorfchat" (village group chat). */
@RestController
public class VillageMediaController {

    /** {@code pending}: the text is still being written (AI job). */
    public record ArticleView(Long id, String section, String sectionTitle, int position, String headline, String body,
                              boolean fallback, boolean pending) {
    }

    public record IssueView(Long id, int issueNumber, boolean midMonth, Integer period, Integer cropYear,
                            long fromGameTime, long publishedGameTime, String headline, List<ArticleView> articles) {
    }

    public record ChatMessageView(Long id, Long groupId, CharacterRef character, String kind, String topic, String text,
                                  String tone, String link, long gameTime, boolean pending) {
    }

    public record ChatGroupView(Long id, String key, String name, List<CharacterRef> members, ChatMessageView last) {
    }

    public record ChatPostRequest(@NotBlank @Size(max = 1000) String text) {
    }

    public record ChatPostView(ChatMessageView message, boolean pacingActive) {
    }

    private final SavegameContext context;
    private final VillageNewspaperService newspaper;
    private final VillageChatService chat;
    private final ApiMapper mapper;

    public VillageMediaController(SavegameContext context, VillageNewspaperService newspaper, VillageChatService chat,
                                  ApiMapper mapper) {
        this.context = context;
        this.newspaper = newspaper;
        this.chat = chat;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------------------------------ D1

    /** All issues, newest first, with their articles. */
    @GetMapping("/api/newspaper")
    @Transactional(readOnly = true)
    public List<IssueView> issues() {
        Savegame sg = context.requireActive();
        return newspaper.list(sg).stream().map(this::view).toList();
    }

    private IssueView view(NewspaperIssue i) {
        return new IssueView(i.getId(), i.getIssueNumber(), i.isMidMonth(), i.getPeriod(), i.getCropYear(),
                i.getFromGameTime(), i.getPublishedGameTime(), i.getHeadline(),
                newspaper.articles(i).stream().map(VillageMediaController::view).toList());
    }

    private static ArticleView view(NewspaperArticle a) {
        return new ArticleView(a.getId(), a.getSection(), VillageNewsService.Section.valueOf(a.getSection()).title(),
                a.getPosition(), a.getHeadline(), a.getBody(), a.isFallback(), a.getBody() == null);
    }

    // ------------------------------------------------------------------------------------------ D2

    @GetMapping("/api/chat/groups")
    @Transactional
    public List<ChatGroupView> groups() {
        Savegame sg = context.requireActive();
        return chat.groups(sg).stream().map(g -> new ChatGroupView(g.group().getId(), g.group().getGroupKey(),
                g.group().getName(), g.members().stream().map(mapper::ref).toList(),
                g.last() == null ? null : view(g.last()))).toList();
    }

    @GetMapping("/api/chat/groups/{id}/messages")
    @Transactional(readOnly = true)
    public List<ChatMessageView> messages(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        return chat.messages(context.requireActive(), id, limit).stream().map(this::view).toList();
    }

    @PostMapping("/api/chat/groups/{id}/messages")
    @Transactional
    public ChatPostView post(@PathVariable Long id, @Valid @RequestBody ChatPostRequest r) {
        VillageChatService.PlayerPost p = chat.write(context.requireActive(), id, r.text());
        return new ChatPostView(view(p.message()), p.pacingActive());
    }

    private ChatMessageView view(ChatMessage m) {
        return new ChatMessageView(m.getId(), m.getGroupId(), m.getCharacter() == null ? null : mapper.ref(m.getCharacter()),
                m.getKind(), m.getTopic(), m.getText(), m.getTone(), m.getLink(), m.getGameTime(), m.isPending());
    }
}
