package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Employee fact file (skill, salary, needs). Purely a tool figure - no FS25 worker parameter is touched. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "employee")
public class Employee extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_role", nullable = false, length = 32)
    private JobRole jobRole;

    @Column(name = "skill", nullable = false)
    private int skill;

    @Column(name = "monthly_salary", nullable = false)
    private long monthlySalary;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private EmployeeStatus status;

    @Column(name = "hired_at_game_time", nullable = false)
    private long hiredAtGameTime;

    @Column(name = "terminated_at_game_time")
    private Long terminatedAtGameTime;

    @Column(name = "pay_fairness", nullable = false)
    private double payFairness;

    @Column(name = "workload", nullable = false)
    private double workload;

    @Column(name = "appreciation", nullable = false)
    private double appreciation;

    @Column(name = "needs_updated_at_game_time", nullable = false)
    private long needsUpdatedAtGameTime;

    @Column(name = "low_satisfaction_since_game_time")
    private Long lowSatisfactionSinceGameTime;

    @Column(name = "warning_sent", nullable = false)
    private boolean warningSent;

    @Column(name = "salary_overdue", nullable = false)
    private boolean salaryOverdue;

    @Column(name = "last_conversation_game_time")
    private Long lastConversationGameTime;

    @Column(name = "last_effect_multiplier", nullable = false)
    private double lastEffectMultiplier;

    @Column(name = "time_off_until_game_time")
    private Long timeOffUntilGameTime;

    @Column(name = "next_salary_due_game_time", nullable = false)
    private long nextSalaryDueGameTime;
}
