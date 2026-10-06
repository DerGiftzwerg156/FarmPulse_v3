package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D7: a termination of cooperative shares, repaid at nominal when due. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "coop_share_notice")
public class CoopShareNotice extends SavegameScoped {

    @Column(name = "shares", nullable = false)
    private int shares;

    @Column(name = "noticed_game_time", nullable = false)
    private long noticedGameTime;

    @Column(name = "due_game_time", nullable = false)
    private long dueGameTime;

    @Column(name = "paid_game_time")
    private Long paidGameTime;
}
