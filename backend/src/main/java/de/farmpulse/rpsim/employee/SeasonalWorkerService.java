package de.farmpulse.rpsim.employee;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-A5: the fixed-term contract of a seasonal worker ends at the start of the month after
 * contract-end-period (the salary of the last month is paid before by the payroll, which runs on the game time advance
 * ahead of the day events). The worker leaves with a farewell mail; with return-satisfaction or more he applies again
 * to the first seasonal posting of the next year (HiringService), his character and trust stay.
 */
@Service
public class SeasonalWorkerService {

    private final EmployeeRepository employees;
    private final SavegameRepository savegames;
    private final SatisfactionService satisfaction;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final ApplicationEventPublisher publisher;
    private final RpsimProperties props;

    public SeasonalWorkerService(EmployeeRepository employees, SavegameRepository savegames,
                                 SatisfactionService satisfaction, NarrationRequestService narration, DiaryService diary,
                                 ApplicationEventPublisher publisher, RpsimProperties props) {
        this.employees = employees;
        this.savegames = savegames;
        this.satisfaction = satisfaction;
        this.narration = narration;
        this.diary = diary;
        this.publisher = publisher;
        this.props = props;
    }

    @EventListener
    @Order(62)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (Employee w : employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.SEASONAL_WORKER)) {
            if (w.getContractEndsAtGameTime() != null && sg.getCurrentGameTime() >= w.getContractEndsAtGameTime()) {
                end(sg, w);
            }
        }
    }

    void end(Savegame sg, Employee w) {
        long now = sg.getCurrentGameTime();
        double score = satisfaction.needs(w).score();
        boolean comeBack = score >= props.getFormulas().getSeasonalWorker().getReturnSatisfaction();
        w.setSeasonEndSatisfaction(score);
        w.setStatus(EmployeeStatus.TERMINATED);
        w.setTerminatedAtGameTime(now);
        w.getCharacter().setStatus(CharacterStatus.TERMINATED);
        w.getCharacter().setTerminationReason(TerminationReason.CONTRACT_ENDED);
        w.getCharacter().setLeftAtGameTime(now);
        Double hours = sg.isWorkforceTracked() ? (w.getWorkedMsMonth() + w.getWorkedMsLastMonth())
                / (double) GameTime.hours(1) : null;
        narration.request(sg, NarrationEventType.SEASONAL_WORKER_FAREWELL).from(w.getCharacter())
                .facts(NarrationFacts.builder().put("comeBack", comeBack)
                        .put("workedHours", hours == null ? "" : Math.round(hours))
                        .put("hoursNote", hours == null ? "" : "Ich war zuletzt " + Math.round(hours) + " Stunden im Einsatz.")
                        .put("returnNote", comeBack ? "Nächstes Jahr komme ich gern wieder!" : "").build())
                .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, w.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", w.getCharacter().getName() + " – Saison beendet", "Der befristete Vertrag als "
                + "Erntehelfer/in ist ausgelaufen." + (comeBack ? " Kommt nächstes Jahr gern wieder." : ""),
                SatisfactionService.RELATED, w.getId());
        publisher.publishEvent(new RosterChangedEvent(sg.getId()));
    }
}
