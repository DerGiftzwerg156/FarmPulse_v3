package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Applicant pool per job posting (skill and salary are deterministic and fixed). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "job_application")
public class JobApplication extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "posting_id")
    private JobPosting posting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "skill", nullable = false)
    private int skill;

    @Column(name = "expected_salary", nullable = false)
    private long expectedSalary;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private JobApplicationStatus status;

    @Column(name = "created_at_game_time", nullable = false)
    private long createdAtGameTime;
}
