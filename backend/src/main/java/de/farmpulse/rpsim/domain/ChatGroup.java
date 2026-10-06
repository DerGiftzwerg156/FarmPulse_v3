package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D2: a group of the village chat (DORF, NACHBARN, CLUB:key). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_group")
public class ChatGroup extends SavegameScoped {

    @Column(name = "group_key", nullable = false, length = 64)
    private String groupKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** Pacing: a player post changes trust once per group within village-chat.pacing-days. */
    @Column(name = "last_player_post_game_time")
    private Long lastPlayerPostGameTime;
}
