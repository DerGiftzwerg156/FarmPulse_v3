package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.InterviewRequest;
import de.farmpulse.rpsim.api.Requests.JobPostingRequest;
import de.farmpulse.rpsim.api.Requests.RaiseRequest;
import de.farmpulse.rpsim.api.Requests.TimeOffRequest;
import de.farmpulse.rpsim.api.Views.ApplicationView;
import de.farmpulse.rpsim.api.Views.EmployeeView;
import de.farmpulse.rpsim.api.Views.JobPostingView;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmployeeController {

    private final SavegameContext context;
    private final HiringService hiring;
    private final SatisfactionService satisfaction;
    private final EmployeeRepository employees;
    private final ApiMapper mapper;

    public EmployeeController(SavegameContext context, HiringService hiring, SatisfactionService satisfaction,
                              EmployeeRepository employees, ApiMapper mapper) {
        this.context = context;
        this.hiring = hiring;
        this.satisfaction = satisfaction;
        this.employees = employees;
        this.mapper = mapper;
    }

    @PostMapping("/api/job-postings")
    @Transactional
    public JobPostingView post(@Valid @RequestBody JobPostingRequest r) {
        return mapper.posting(hiring.createPosting(context.requireActive(), r.jobRole()));
    }

    @GetMapping("/api/job-postings")
    @Transactional(readOnly = true)
    public List<JobPostingView> postings() {
        return hiring.postings(context.requireActive()).stream().map(mapper::posting).toList();
    }

    @GetMapping("/api/job-postings/{id}/applications")
    @Transactional(readOnly = true)
    public List<ApplicationView> applications(@PathVariable Long id) {
        return hiring.applications(context.requireActive(), id).stream().map(mapper::application).toList();
    }

    /** Simulated interview by mail or call; skill and salary stay fixed. */
    @PostMapping("/api/job-postings/{id}/applications/{appId}/interview-question")
    @Transactional
    public void interview(@PathVariable Long id, @PathVariable Long appId, @Valid @RequestBody InterviewRequest r) {
        hiring.interviewQuestion(context.requireActive(), id, appId, r.question(), r.channel());
    }

    @PostMapping("/api/job-postings/{id}/applications/{appId}/hire")
    @Transactional
    public EmployeeView hire(@PathVariable Long id, @PathVariable Long appId) {
        return mapper.employee(hiring.hire(context.requireActive(), id, appId));
    }

    @GetMapping("/api/employees")
    @Transactional(readOnly = true)
    public List<EmployeeView> employees() {
        return hiring.list(context.requireActive()).stream().map(mapper::employee).toList();
    }

    private Employee active(Savegame sg, Long id) {
        return employees.findById(id).filter(e -> e.getSavegame().getId().equals(sg.getId())
                && e.getStatus() == EmployeeStatus.ACTIVE).orElseThrow(() -> new NotFoundException("employee " + id));
    }

    @PostMapping("/api/employees/{id}/raise")
    @Transactional
    public EmployeeView raise(@PathVariable Long id, @Valid @RequestBody RaiseRequest r) {
        Employee e = active(context.requireActive(), id);
        satisfaction.raise(e, r.newSalary());
        return mapper.employee(e);
    }

    @PostMapping("/api/employees/{id}/time-off")
    @Transactional
    public EmployeeView timeOff(@PathVariable Long id, @Valid @RequestBody TimeOffRequest r) {
        Employee e = active(context.requireActive(), id);
        satisfaction.timeOff(e, r.days());
        return mapper.employee(e);
    }

    @DeleteMapping("/api/employees/{id}")
    @Transactional
    public EmployeeView dismiss(@PathVariable Long id) {
        return mapper.employee(hiring.dismiss(context.requireActive(), id));
    }
}
