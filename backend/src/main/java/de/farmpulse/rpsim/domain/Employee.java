package de.farmpulse.rpsim.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Roadmap V2 R2-A5: on strike since (null = working). The salary keeps running. */
    @Column(name = "strike_since_game_time")
    private Long strikeSinceGameTime;

    /** R2-A4: last cumulative worked game time reported by the mod (farm_facts.workforce.workedGameMs). */
    @Column(name = "worked_ms_seen")
    private Long workedMsSeen;

    /** R2-A4: worked game time since the last daily workload evaluation. */
    @Column(name = "worked_ms_today", nullable = false)
    private long workedMsToday;

    /** R2-A4: worked game time in the current game month. */
    @Column(name = "worked_ms_month", nullable = false)
    private long workedMsMonth;

    @Column(name = "worked_ms_last_month", nullable = false)
    private long workedMsLastMonth;

    /** R2-A7: last warning mail of an animal keeper about food / water in the stables. */
    @Column(name = "last_stable_warning_game_time")
    private Long lastStableWarningGameTime;

    /** Finished trainings of a machine operator, comma separated {@link Training} names ("" = none). */
    @Column(name = "trainings", nullable = false, length = 255)
    private String trainings = "";

    /** The training the employee is attending right now (away until trainingUntilGameTime), null = none. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "training_in_progress", length = 32)
    private Training trainingInProgress;

    @Column(name = "training_until_game_time")
    private Long trainingUntilGameTime;

    /** Roadmap V3 R3-P2: end of the training of an apprentice (null = no apprentice). */
    @Column(name = "apprenticeship_ends_at_game_time")
    private Long apprenticeshipEndsAtGameTime;

    public Set<Training> trainingSet() {
        Set<Training> set = EnumSet.noneOf(Training.class);
        if (trainings != null && !trainings.isBlank()) {
            Arrays.stream(trainings.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(s -> {
                try {
                    set.add(Training.valueOf(s));
                } catch (IllegalArgumentException ignored) {
                    // a training removed from the catalog is dropped silently
                }
            });
        }
        return set;
    }

    public boolean hasTraining(Training t) {
        return trainingSet().contains(t);
    }

    public void addTraining(Training t) {
        Set<Training> set = trainingSet();
        set.add(t);
        trainings = set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    public void removeTraining(Training t) {
        Set<Training> set = trainingSet();
        set.remove(t);
        trainings = set.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
