package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorPeriodRepository extends JpaRepository<InvestorPeriod, Long> {

    List<InvestorPeriod> findByObligationOrderByPeriodKeyAsc(InvestorObligation obligation);

    Optional<InvestorPeriod> findByObligationAndPeriodKey(InvestorObligation obligation, long periodKey);

    List<InvestorPeriod> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, String status);

    Optional<InvestorPeriod> findByCaseId(Long caseId);

    Optional<InvestorPeriod> findByReminderCaseId(Long caseId);
}
