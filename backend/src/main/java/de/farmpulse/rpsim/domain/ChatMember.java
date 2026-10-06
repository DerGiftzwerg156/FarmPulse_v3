package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D2: a character in a chat group. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_member")
public class ChatMember extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "group_id", nullable = false)
    private Long groupId;
}
