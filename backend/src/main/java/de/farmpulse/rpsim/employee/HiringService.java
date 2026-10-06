package de.farmpulse.rpsim.employee;

import java.util.ArrayList;
import java.util.List;

import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterGeneratorService.Spec;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobApplicationStatus;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobPostingStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.JobApplicationRepository;
import de.farmpulse.rpsim.repository.JobPostingRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job market (technical concept "Kündigung & Bewerbung"): the engine generates 3-5 candidates with deterministic
 * skill values and matching salary expectations; the AI writes the applications. Interviews never change skill or
 * salary. Hiring rejects the remaining applicants automatically.
 * <p>
 * Owner decisions 2026-10-06: the applications arrive the next game day, each at a random time between
 * application-hour-min and application-hour-max o'clock (mail and list entry). A hired employee starts with the next
 * month (PENDING_START until then: no helper, no salary, frozen needs, no actions; first salary on the first working
 * day); seasonal workers start at once. Cancelling before the first working day costs severance-factor x the monthly
 * salary (HARSH: severance-factor-harsh x) and the candidate answers with a mail.
 */
@Service
public class HiringService {

    public static final String RELATED = "JOB_POSTING";

    private final JobPostingRepository postings;
    private final JobApplicationRepository applications;
    private final EmployeeRepository employees;
    private final CharacterGeneratorService generator;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;
    private final ApplicationEventPublisher publisher;
    private final OutboxService outbox;
    private final SavegameRepository savegames;

    public HiringService(JobPostingRepository postings, JobApplicationRepository applications, EmployeeRepository employees,
                         CharacterGeneratorService generator, NarrationRequestService narration, DiaryService diary,
                         RandomSource random, RpsimProperties props, GameTime gameTime, ApplicationEventPublisher publisher,
                         OutboxService outbox, SavegameRepository savegames) {
        this.outbox = outbox;
        this.savegames = savegames;
        this.postings = postings;
        this.applications = applications;
        this.employees = employees;
        this.generator = generator;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
        this.publisher = publisher;
    }

    private RpsimProperties.Hiring cfg() {
        return props.getFormulas().getHiring();
    }

    /** Deterministic skill/salary pair for a role (also used for the onboarding start staff). */
    public record Offer(int skill, long salary) {
    }

    public Offer rollOffer(JobRole role, RandomSource r) {
        int skill = r.intBetween(cfg().getSkillMin(), cfg().getSkillMax());
        double base = cfg().getBaseSalary().getOrDefault(role.name(), 2400.0);
        long salary = Math.round(base * (1 + cfg().getSalarySkillFactor() * (skill - 50) / 50.0) / 10.0) * 10;
        return new Offer(skill, salary);
    }

    /**
     * "Schulungen": a machine operator applicant brings one random training along with applicant-chance and expects
     * applicant-salary-premium more salary then (rounded to 10 €). Other roles bring none.
     */
    public record TrainedOffer(int skill, long salary, Training training) {
    }

    public TrainedOffer rollApplicant(JobRole role, RandomSource r) {
        if (role == JobRole.APPRENTICE) {
            // R3-P2: low skill and a fixed salary, no training
            RpsimProperties.Apprentice a = props.getFormulas().getApprentice();
            return new TrainedOffer(r.intBetween(a.getSkillMin(), a.getSkillMax()), a.getSalary(), null);
        }
        if (role == JobRole.SEASONAL_WORKER) {
            // R31-A5: skill in [skill-min, skill-max], machine operator salary x salary-factor, no training
            RpsimProperties.SeasonalWorker s = seasonal();
            int skill = r.intBetween(s.getSkillMin(), s.getSkillMax());
            return new TrainedOffer(skill, seasonalSalary(skill), null);
        }
        Offer o = rollOffer(role, r);
        RpsimProperties.Trainings t = props.getFormulas().getTraining();
        if (role != JobRole.MACHINE_OPERATOR || !r.chance(t.getApplicantChance())) {
            return new TrainedOffer(o.skill(), o.salary(), null);
        }
        Training training = r.pick(List.of(Training.values()));
        long salary = Math.round(o.salary() * (1 + t.getApplicantSalaryPremium()) / 10.0) * 10;
        return new TrainedOffer(o.skill(), salary, training);
    }

    private RpsimProperties.SeasonalWorker seasonal() {
        return props.getFormulas().getSeasonalWorker();
    }

    /** R31-A5: machine operator salary (hiring formula at the skill) x salary-factor, rounded to 10 €. */
    public long seasonalSalary(int skill) {
        double base = cfg().getBaseSalary().getOrDefault(JobRole.MACHINE_OPERATOR.name(), 2400.0);
        double operator = base * (1 + cfg().getSalarySkillFactor() * (skill - 50) / 50.0);
        return Math.round(operator * seasonal().getSalaryFactor() / 10.0) * 10;
    }

    /** R31-A5: seasonal jobs only in the posting periods and with fewer than max-workers seasonal workers. */
    void requireSeasonalPlace(Savegame sg, JobRole role) {
        if (role != JobRole.SEASONAL_WORKER) {
            return;
        }
        if (!seasonal().getPostingPeriods().contains(gameTime.periodOfYear(sg, sg.getCurrentGameTime()))) {
            throw new BusinessRuleException("SEASONAL_OUT_OF_SEASON", "Saisonkräfte gibt es nur vor und in der Erntezeit.");
        }
        if (employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.SEASONAL_WORKER).size()
                >= seasonal().getMaxWorkers()) {
            throw new BusinessRuleException("SEASONAL_LIMIT", "Es sind höchstens " + seasonal().getMaxWorkers()
                    + " Saisonkräfte gleichzeitig möglich.");
        }
    }

    /** R31-A5: end of the seasonal contract = start of the month after contract-end-period. */
    long seasonalContractEnd(Savegame sg) {
        long now = sg.getCurrentGameTime();
        int period = gameTime.periodOfYear(sg, now);
        int months = Math.floorMod(seasonal().getContractEndPeriod() - period, GameTime.PERIODS_PER_YEAR) + 1;
        return gameTime.addMonths(sg, now, months);
    }

    /**
     * R31-A5: former seasonal workers who left at the end of last season with return-satisfaction or more and have not
     * applied again since.
     */
    List<Employee> returningSeasonalWorkers(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        return employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.TERMINATED, JobRole.SEASONAL_WORKER).stream()
                .filter(e -> e.getSeasonEndSatisfaction() != null
                        && e.getSeasonEndSatisfaction() >= seasonal().getReturnSatisfaction()
                        && e.getTerminatedAtGameTime() != null
                        && month - gameTime.monthIndex(sg, e.getTerminatedAtGameTime()) < GameTime.PERIODS_PER_YEAR
                        && !applications.existsByReturningEmployeeIdAndCreatedAtGameTimeGreaterThanEqual(e.getId(),
                                e.getTerminatedAtGameTime()))
                .toList();
    }

    @Transactional
    public JobPosting createPosting(Savegame sg, JobRole role) {
        requireApprenticePlace(sg, role);
        requireSeasonalPlace(sg, role);
        JobPosting p = new JobPosting();
        p.setSavegame(sg);
        p.setJobRole(role);
        p.setStatus(JobPostingStatus.OPEN);
        p.setCreatedAtGameTime(sg.getCurrentGameTime());
        postings.save(p);
        int count = random.intBetween(cfg().getCandidatesMin(), cfg().getCandidatesMax());
        for (int i = 0; i < count; i++) {
            long seed = random.nextLong();
            Character c = generator.generate(sg, new Spec(CharacterRole.APPLICANT, CharacterCategory.APPLICANT, 0, role), seed);
            RandomSource r = RandomSource.seeded(seed);
            TrainedOffer o = rollApplicant(role, r);
            long arrives = arrivalTime(sg, r);
            JobApplication a = new JobApplication();
            a.setSavegame(sg);
            a.setPosting(p);
            a.setCharacter(c);
            a.setSkill(o.skill());
            a.setExpectedSalary(o.salary());
            a.setTraining(o.training());
            a.setStatus(JobApplicationStatus.PENDING);
            a.setCreatedAtGameTime(sg.getCurrentGameTime());
            a.setArrivesAtGameTime(arrives);
            applications.save(a);
            narration.request(sg, NarrationEventType.JOB_APPLICATION).from(c)
                    .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(role)).put("skill", o.skill())
                            .put("expectedSalary", o.salary()).put("training", trainingTitle(o.training()))
                            .put("trainingNote", trainingNote(o.training())).build())
                    .category(CommunicationCategory.EMPLOYEE).related(RELATED, p.getId())
                    .formLink("/employees?posting=" + p.getId()).delay(arrives - sg.getCurrentGameTime()).submit();
        }
        if (role == JobRole.SEASONAL_WORKER) {
            for (Employee old : returningSeasonalWorkers(sg)) {
                // R31-A5: a well treated seasonal worker of last year applies again (same character, trust stays)
                long salary = seasonalSalary(old.getSkill());
                long arrives = arrivalTime(sg, random);
                JobApplication a = new JobApplication();
                a.setSavegame(sg);
                a.setPosting(p);
                a.setCharacter(old.getCharacter());
                a.setSkill(old.getSkill());
                a.setExpectedSalary(salary);
                a.setReturningEmployeeId(old.getId());
                a.setStatus(JobApplicationStatus.PENDING);
                a.setCreatedAtGameTime(sg.getCurrentGameTime());
                a.setArrivesAtGameTime(arrives);
                applications.save(a);
                narration.request(sg, NarrationEventType.SEASONAL_WORKER_RETURN).from(old.getCharacter())
                        .facts(NarrationFacts.builder().put("skill", old.getSkill()).put("expectedSalary", salary).build())
                        .category(CommunicationCategory.EMPLOYEE).related(RELATED, p.getId())
                        .formLink("/employees?posting=" + p.getId()).delay(arrives - sg.getCurrentGameTime()).submit();
            }
        }
        return p;
    }

    /**
     * Owner decision 2026-10-06: arrival of an application - the next game day at a random minute between
     * application-hour-min and application-hour-max o'clock.
     */
    long arrivalTime(Savegame sg, RandomSource r) {
        long nextDay = (GameTime.dayIndex(sg.getCurrentGameTime()) + 1) * GameTime.MS_PER_DAY;
        int from = cfg().getApplicationHourMin() * 60;
        int to = Math.max(from, cfg().getApplicationHourMax() * 60 - 1);
        return nextDay + r.intBetween(from, to) * 60_000L;
    }

    /** The application has arrived (null = an application from before the arrival times). */
    public static boolean arrived(JobApplication a, long now) {
        return a.getArrivesAtGameTime() == null || a.getArrivesAtGameTime() <= now;
    }

    /** Applications of the posting that have arrived. */
    public List<JobApplication> applications(Savegame sg, Long postingId) {
        long now = sg.getCurrentGameTime();
        return applications.findByPostingOrderByIdAsc(posting(sg, postingId)).stream().filter(a -> arrived(a, now))
                .toList();
    }

    /** Applications of the posting that are still on their way. */
    public boolean applicationsAwaited(JobPosting p) {
        long now = p.getSavegame().getCurrentGameTime();
        return applications.findByPostingOrderByIdAsc(p).stream().anyMatch(a -> !arrived(a, now));
    }

    /** Simulated interview (mail or call): free question, fixed skill/salary; the answer is narrated. */
    @Transactional
    public NarrationJob interviewQuestion(Savegame sg, Long postingId, Long applicationId, String question, Channel channel) {
        JobApplication a = application(sg, postingId, applicationId);
        if (a.getStatus() != JobApplicationStatus.PENDING) {
            throw new BusinessRuleException("APPLICATION_CLOSED", "Diese Bewerbung ist nicht mehr offen.");
        }
        return narration.request(sg, NarrationEventType.INTERVIEW_ANSWER).from(a.getCharacter())
                .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(a.getPosting().getJobRole()))
                        .put("skill", a.getSkill()).put("expectedSalary", a.getExpectedSalary())
                        .put("training", trainingTitle(a.getTraining())).build())
                .playerMessage(question).channel(channel == null ? Channel.MAIL : channel)
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, postingId).submit();
    }

    @Transactional
    public Employee hire(Savegame sg, Long postingId, Long applicationId) {
        JobPosting p = posting(sg, postingId);
        if (p.getStatus() != JobPostingStatus.OPEN) {
            throw new BusinessRuleException("POSTING_CLOSED", "Die Stelle ist bereits besetzt.");
        }
        JobApplication chosen = application(sg, postingId, applicationId);
        requireApprenticePlace(sg, p.getJobRole());
        requireSeasonalPlace(sg, p.getJobRole());
        // seasonal workers start at once (owner decision 2026-10-06), everybody else with the next month
        Employee e = chosen.getReturningEmployeeId() != null
                ? rehire(sg, chosen.getReturningEmployeeId(), chosen.getSkill(), chosen.getExpectedSalary())
                : createEmployee(sg, chosen.getCharacter(), p.getJobRole(), chosen.getSkill(), chosen.getExpectedSalary(),
                        p.getJobRole() == JobRole.SEASONAL_WORKER ? sg.getCurrentGameTime()
                                : gameTime.addMonths(sg, sg.getCurrentGameTime(), 1));
        if (chosen.getTraining() != null) {
            e.addTraining(chosen.getTraining());
            publisher.publishEvent(new RosterChangedEvent(sg.getId())); // the list for the mod with the training
        }
        chosen.setStatus(JobApplicationStatus.HIRED);
        p.setStatus(JobPostingStatus.FILLED);
        p.setFilledEmployeeId(e.getId());
        long now = sg.getCurrentGameTime();
        for (JobApplication other : applications.findByPostingOrderByIdAsc(p)) {
            if (!other.getId().equals(chosen.getId()) && other.getStatus() == JobApplicationStatus.PENDING) {
                other.setStatus(JobApplicationStatus.REJECTED);
                other.getCharacter().setStatus(CharacterStatus.TERMINATED);
                other.getCharacter().setTerminationReason(TerminationReason.REJECTED_APPLICANT);
                // an application still on its way gets its answer only after it arrived
                long delay = arrived(other, now) ? 0 : other.getArrivesAtGameTime() - now + GameTime.hours(1);
                narration.request(sg, NarrationEventType.APPLICATION_REJECTED).from(other.getCharacter())
                        .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(p.getJobRole())).build())
                        .category(CommunicationCategory.EMPLOYEE).related(RELATED, p.getId()).delay(delay).submit();
            }
        }
        String startDate = e.getStatus() == EmployeeStatus.PENDING_START ? startDate(sg, e.getStartsAtGameTime()) : null;
        narration.request(sg, NarrationEventType.EMPLOYEE_WELCOME).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(p.getJobRole()))
                        .put("salary", e.getMonthlySalary()).put("startDate", startDate)
                        .put("startNote", startDate == null ? "" : "Ich fange am " + startDate + " an.").build())
                .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, e.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " eingestellt", generator.jobRoleTitle(p.getJobRole())
                + ", Gehalt " + e.getMonthlySalary() + " €/Monat" + (startDate == null ? "." : ", Arbeitsbeginn am "
                + startDate + "."), SatisfactionService.RELATED, e.getId());
        return e;
    }

    /** "1. Juli": the first day of the month of the game time. */
    String startDate(Savegame sg, long monthStart) {
        return "1. " + CalendarText.month(gameTime.periodOfYear(sg, monthStart));
    }

    /** FS25 period (1 = March) of the first working day of a PENDING_START employee, null otherwise. */
    public Integer startPeriod(Employee e) {
        return e.getStartsAtGameTime() == null ? null : gameTime.periodOfYear(e.getSavegame(), e.getStartsAtGameTime());
    }

    /**
     * Creates an employee with neutral satisfaction (~70) who works at once; used by the onboarding start staff (they
     * count as already employed, technical concept "Mitarbeiter-Ausgangslage").
     */
    @Transactional
    public Employee createEmployee(Savegame sg, Character c, JobRole role, int skill, long salary) {
        return createEmployee(sg, c, role, skill, salary, sg.getCurrentGameTime());
    }

    /**
     * Creates an employee with neutral satisfaction (~70). A start after now (owner decision 2026-10-06: the next month)
     * makes him PENDING_START until then - salary and needs begin with the first working day.
     */
    @Transactional
    public Employee createEmployee(Savegame sg, Character c, JobRole role, int skill, long salary, long start) {
        long now = sg.getCurrentGameTime();
        boolean later = start > now;
        long from = later ? start : now;
        c.setRole(CharacterRole.EMPLOYEE);
        c.setCategory(CharacterCategory.EMPLOYEE);
        c.setShortDescription(generator.shortDescription(c, role));
        double startValue = props.getFormulas().getSatisfaction().getStartValue();
        Employee e = new Employee();
        e.setSavegame(sg);
        e.setCharacter(c);
        e.setJobRole(role);
        e.setSkill(skill);
        e.setMonthlySalary(salary);
        e.setStatus(later ? EmployeeStatus.PENDING_START : EmployeeStatus.ACTIVE);
        e.setHiredAtGameTime(now);
        e.setStartsAtGameTime(later ? start : null);
        e.setPayFairness(startValue);
        e.setWorkload(startValue);
        e.setAppreciation(startValue);
        e.setNeedsUpdatedAtGameTime(from);
        e.setLastEffectMultiplier(1.0);
        // T-08: salaries are paid at the start of each FS25 period; a later start is paid on the first working day
        e.setNextSalaryDueGameTime(later ? start : gameTime.addMonths(sg, now, 1));
        if (role == JobRole.APPRENTICE) {
            // R3-P2: training of training-years FS25 years
            e.setApprenticeshipEndsAtGameTime(gameTime.addMonths(sg, from,
                    (long) GameTime.PERIODS_PER_YEAR * props.getFormulas().getApprentice().getTrainingYears()));
        }
        if (role == JobRole.SEASONAL_WORKER) {
            e.setContractEndsAtGameTime(seasonalContractEnd(sg)); // R31-A5: fixed term
        }
        Employee saved = employees.save(e);
        publisher.publishEvent(new RosterChangedEvent(sg.getId())); // R2-A0
        return saved;
    }

    /**
     * R31-A5: a former seasonal worker comes back - his employee record (and character with trust and memory) is
     * reactivated with fresh needs, the new salary and a new contract end.
     */
    private Employee rehire(Savegame sg, Long employeeId, int skill, long salary) {
        Employee e = employees.findById(employeeId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("employee " + employeeId));
        long now = sg.getCurrentGameTime();
        double start = props.getFormulas().getSatisfaction().getStartValue();
        Character c = e.getCharacter();
        c.setRole(CharacterRole.EMPLOYEE);
        c.setCategory(CharacterCategory.EMPLOYEE);
        c.setStatus(CharacterStatus.ACTIVE);
        c.setTerminationReason(null);
        c.setLeftAtGameTime(null);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setSkill(skill);
        e.setMonthlySalary(salary);
        e.setHiredAtGameTime(now);
        e.setTerminatedAtGameTime(null);
        e.setSeasonEndSatisfaction(null);
        e.setPayFairness(start);
        e.setWorkload(start);
        e.setAppreciation(start);
        e.setNeedsUpdatedAtGameTime(now);
        e.setLowSatisfactionSinceGameTime(null);
        e.setWarningSent(false);
        e.setSalaryOverdue(false);
        e.setStrikeSinceGameTime(null);
        e.setTimeOffUntilGameTime(null);
        e.setLastEffectMultiplier(1.0);
        e.setWorkedMsToday(0);
        e.setWorkedMsMonth(0);
        e.setNextSalaryDueGameTime(gameTime.addMonths(sg, now, 1));
        e.setContractEndsAtGameTime(seasonalContractEnd(sg));
        publisher.publishEvent(new RosterChangedEvent(sg.getId()));
        return e;
    }

    /** R3-P2: at most max-apprentices apprentices at a time (hired ones that start next month count, too). */
    void requireApprenticePlace(Savegame sg, JobRole role) {
        if (role == JobRole.APPRENTICE && employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE,
                JobRole.APPRENTICE).size() + employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.PENDING_START,
                JobRole.APPRENTICE).size() >= props.getFormulas().getApprentice().getMaxApprentices()) {
            throw new BusinessRuleException("APPRENTICE_LIMIT", "Es sind höchstens "
                    + props.getFormulas().getApprentice().getMaxApprentices() + " Azubis gleichzeitig möglich.");
        }
    }

    /** Dismissal by the player; before the first working day a cancellation with severance. */
    @Transactional
    public Employee dismiss(Savegame sg, Long employeeId) {
        Employee e = employees.findById(employeeId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("employee " + employeeId));
        if (e.getStatus() == EmployeeStatus.PENDING_START) {
            return cancel(sg, e);
        }
        if (e.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessRuleException("EMPLOYEE_INACTIVE", "Mitarbeiter ist nicht mehr angestellt.");
        }
        e.setStatus(EmployeeStatus.TERMINATED);
        e.setTerminatedAtGameTime(sg.getCurrentGameTime());
        e.getCharacter().setStatus(CharacterStatus.TERMINATED);
        e.getCharacter().setTerminationReason(TerminationReason.DISMISSED);
        e.getCharacter().setLeftAtGameTime(sg.getCurrentGameTime());
        diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " entlassen", "Das Arbeitsverhältnis wurde beendet.",
                SatisfactionService.RELATED, e.getId());
        publisher.publishEvent(new RosterChangedEvent(sg.getId())); // R2-A0
        return e;
    }

    /** Owner decision 2026-10-06: severance for a cancellation before the first working day (whole €). */
    public long severance(Employee e) {
        double factor = e.getSavegame().getTonePreset() == TonePreset.HARSH ? cfg().getSeveranceFactorHarsh()
                : cfg().getSeveranceFactor();
        return Math.round(e.getMonthlySalary() * factor);
    }

    /**
     * Owner decision 2026-10-06: the hiring is taken back before the first working day - severance (SEVERANCE), the
     * candidate answers with a mail.
     */
    private Employee cancel(Savegame sg, Employee e) {
        long now = sg.getCurrentGameTime();
        long severance = severance(e);
        String name = e.getCharacter().getName();
        if (severance > 0) {
            outbox.money(sg, -severance, MoneyReason.SEVERANCE, "Abfindung " + name,
                    new Related(SatisfactionService.RELATED, e.getId()));
        }
        e.setStatus(EmployeeStatus.TERMINATED);
        e.setTerminatedAtGameTime(now);
        e.getCharacter().setStatus(CharacterStatus.TERMINATED);
        e.getCharacter().setTerminationReason(TerminationReason.DISMISSED);
        e.getCharacter().setLeftAtGameTime(now);
        narration.request(sg, NarrationEventType.HIRING_CANCELLED).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(e.getJobRole()))
                        .put("severance", severance).build())
                .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, e.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", "Einstellung von " + name + " zurückgenommen",
                "Vor dem ersten Arbeitstag abgesagt, Abfindung " + severance + " €.", SatisfactionService.RELATED, e.getId());
        return e;
    }

    /**
     * Owner decision 2026-10-06: PENDING_START employees whose first working day has come start working - before the
     * salaries of the same game time (PayrollScheduler, order 10) so the first salary is paid on that day.
     */
    @EventListener
    @Order(5)
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent ev) {
        savegames.findById(ev.savegameId()).ifPresent(this::startDue);
    }

    @Transactional
    public boolean startDue(Savegame sg) {
        long now = sg.getCurrentGameTime();
        boolean started = false;
        for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.PENDING_START)) {
            if (e.getStartsAtGameTime() != null && e.getStartsAtGameTime() > now) {
                continue;
            }
            e.setStatus(EmployeeStatus.ACTIVE);
            diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " fängt heute an",
                    "Erster Arbeitstag als " + generator.jobRoleTitle(e.getJobRole()) + ".", SatisfactionService.RELATED,
                    e.getId());
            started = true;
        }
        if (started) {
            publisher.publishEvent(new RosterChangedEvent(sg.getId())); // R2-A0: the list for the mod
        }
        return started;
    }

    /** T-08: "days per period" changed - the first working day (a month start) keeps its month. */
    @EventListener
    @Transactional
    public void onCalendarChanged(CalendarChangedEvent ev) {
        savegames.findById(ev.savegameId()).ifPresent(sg -> {
            for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.PENDING_START)) {
                e.setStartsAtGameTime(ev.remap(e.getStartsAtGameTime()));
                e.setNextSalaryDueGameTime(ev.remap(e.getNextSalaryDueGameTime()));
                e.setNeedsUpdatedAtGameTime(ev.remap(e.getNeedsUpdatedAtGameTime()));
                e.setApprenticeshipEndsAtGameTime(ev.remap(e.getApprenticeshipEndsAtGameTime()));
            }
        });
    }

    /** Title of a training for the narration facts, "keine" without one (the AI must not invent a training). */
    private static String trainingTitle(Training t) {
        return t == null ? "keine" : t.title();
    }

    /** Sentence of the fallback application text. */
    private static String trainingNote(Training t) {
        return t == null ? "" : "Die Schulung „" + t.title() + "“ habe ich bereits absolviert.";
    }

    public List<JobPosting> postings(Savegame sg) {
        return postings.findBySavegameOrderByIdDesc(sg);
    }

    private JobPosting posting(Savegame sg, Long id) {
        return postings.findById(id).filter(p -> p.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("job posting " + id));
    }

    private JobApplication application(Savegame sg, Long postingId, Long id) {
        JobApplication a = applications.findById(id).orElseThrow(() -> new NotFoundException("application " + id));
        if (!a.getPosting().getId().equals(postingId) || !a.getSavegame().getId().equals(sg.getId())
                || !arrived(a, sg.getCurrentGameTime())) {
            throw new NotFoundException("application " + id);
        }
        return a;
    }

    public List<Employee> list(Savegame sg) {
        return new ArrayList<>(employees.findBySavegameOrderByIdAsc(sg));
    }
}
