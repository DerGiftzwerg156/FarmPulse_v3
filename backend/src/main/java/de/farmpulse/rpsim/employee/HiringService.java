package de.farmpulse.rpsim.employee;

import java.util.ArrayList;
import java.util.List;

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
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.JobApplicationRepository;
import de.farmpulse.rpsim.repository.JobPostingRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job market (technical concept "Kündigung & Bewerbung"): the engine generates 3-5 candidates with deterministic
 * skill values and matching salary expectations; the AI writes the applications. Interviews never change skill or
 * salary. Hiring rejects the remaining applicants automatically.
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

    public HiringService(JobPostingRepository postings, JobApplicationRepository applications, EmployeeRepository employees,
                         CharacterGeneratorService generator, NarrationRequestService narration, DiaryService diary,
                         RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.postings = postings;
        this.applications = applications;
        this.employees = employees;
        this.generator = generator;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
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

    @Transactional
    public JobPosting createPosting(Savegame sg, JobRole role) {
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
            Offer o = rollOffer(role, RandomSource.seeded(seed));
            JobApplication a = new JobApplication();
            a.setSavegame(sg);
            a.setPosting(p);
            a.setCharacter(c);
            a.setSkill(o.skill());
            a.setExpectedSalary(o.salary());
            a.setStatus(JobApplicationStatus.PENDING);
            a.setCreatedAtGameTime(sg.getCurrentGameTime());
            applications.save(a);
            narration.request(sg, NarrationEventType.JOB_APPLICATION).from(c)
                    .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(role)).put("skill", o.skill())
                            .put("expectedSalary", o.salary()).build())
                    .category(CommunicationCategory.EMPLOYEE).related(RELATED, p.getId())
                    .formLink("/employees?posting=" + p.getId()).submit();
        }
        return p;
    }

    public List<JobApplication> applications(Savegame sg, Long postingId) {
        return applications.findByPostingOrderByIdAsc(posting(sg, postingId));
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
                        .put("skill", a.getSkill()).put("expectedSalary", a.getExpectedSalary()).build())
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
        Employee e = createEmployee(sg, chosen.getCharacter(), p.getJobRole(), chosen.getSkill(), chosen.getExpectedSalary());
        chosen.setStatus(JobApplicationStatus.HIRED);
        p.setStatus(JobPostingStatus.FILLED);
        p.setFilledEmployeeId(e.getId());
        for (JobApplication other : applications.findByPostingOrderByIdAsc(p)) {
            if (!other.getId().equals(chosen.getId()) && other.getStatus() == JobApplicationStatus.PENDING) {
                other.setStatus(JobApplicationStatus.REJECTED);
                other.getCharacter().setStatus(CharacterStatus.TERMINATED);
                other.getCharacter().setTerminationReason(TerminationReason.REJECTED_APPLICANT);
                narration.request(sg, NarrationEventType.APPLICATION_REJECTED).from(other.getCharacter())
                        .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(p.getJobRole())).build())
                        .category(CommunicationCategory.EMPLOYEE).related(RELATED, p.getId()).submit();
            }
        }
        narration.request(sg, NarrationEventType.EMPLOYEE_WELCOME).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("jobRole", generator.jobRoleTitle(p.getJobRole()))
                        .put("salary", e.getMonthlySalary()).build())
                .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, e.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " eingestellt", generator.jobRoleTitle(p.getJobRole())
                + ", Gehalt " + e.getMonthlySalary() + " €/Monat.", SatisfactionService.RELATED, e.getId());
        return e;
    }

    /** Creates an employee with neutral satisfaction (~70); used by hiring and the onboarding start staff. */
    @Transactional
    public Employee createEmployee(Savegame sg, Character c, JobRole role, int skill, long salary) {
        c.setRole(CharacterRole.EMPLOYEE);
        c.setCategory(CharacterCategory.EMPLOYEE);
        c.setShortDescription(generator.shortDescription(c, role));
        double start = props.getFormulas().getSatisfaction().getStartValue();
        Employee e = new Employee();
        e.setSavegame(sg);
        e.setCharacter(c);
        e.setJobRole(role);
        e.setSkill(skill);
        e.setMonthlySalary(salary);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setHiredAtGameTime(sg.getCurrentGameTime());
        e.setPayFairness(start);
        e.setWorkload(start);
        e.setAppreciation(start);
        e.setNeedsUpdatedAtGameTime(sg.getCurrentGameTime());
        e.setLastEffectMultiplier(1.0);
        // T-08: salaries are paid at the start of each FS25 period
        e.setNextSalaryDueGameTime(gameTime.addMonths(sg, sg.getCurrentGameTime(), 1));
        return employees.save(e);
    }

    /** Dismissal by the player. */
    @Transactional
    public Employee dismiss(Savegame sg, Long employeeId) {
        Employee e = employees.findById(employeeId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("employee " + employeeId));
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
        return e;
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
        if (!a.getPosting().getId().equals(postingId) || !a.getSavegame().getId().equals(sg.getId())) {
            throw new NotFoundException("application " + id);
        }
        return a;
    }

    public List<Employee> list(Savegame sg) {
        return new ArrayList<>(employees.findBySavegameOrderByIdAsc(sg));
    }
}
