package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3 R3-P1: a deadline the office clerk reminded of (once per subject and deadline). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "office_reminder")
public class OfficeReminder extends SavegameScoped {

    @Column(name = "subject_type", nullable = false, length = 32)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private long subjectId;

    @Column(name = "deadline_game_time", nullable = false)
    private long deadlineGameTime;

    @Column(name = "sent_game_time", nullable = false)
    private long sentGameTime;
}
