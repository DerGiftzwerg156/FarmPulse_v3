package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-D2: a message in a chat group - of a character (ANNOUNCEMENT, GOSSIP, HELP, CONGRATULATION, REPLY;
 * text written by a narration job while {@code pending}) or of the player (PLAYER, character null).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_message")
public class ChatMessage extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "topic", length = 64)
    private String topic;

    @Lob
    @Column(name = "text")
    private String text;

    /** Tone class of a player post. */
    @Column(name = "tone", length = 16)
    private String tone;

    /** Tablet link of a help request. */
    @Column(name = "link", length = 255)
    private String link;

    @Column(name = "related_case_id")
    private Long relatedCaseId;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "pending", nullable = false)
    private boolean pending;
}
