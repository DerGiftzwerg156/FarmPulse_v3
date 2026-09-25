package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Job posting; the HiringService generates the applicant pool. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "job_posting")
public class JobPosting extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_role", nullable = false, length = 32)
    private JobRole jobRole;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private JobPostingStatus status;

    @Column(name = "created_at_game_time", nullable = false)
    private long createdAtGameTime;

    @Column(name = "filled_employee_id")
    private Long filledEmployeeId;
}
