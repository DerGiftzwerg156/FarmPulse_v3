package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorObligation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorObligationRepository extends JpaRepository<InvestorObligation, Long> {

    List<InvestorObligation> findByContractOrderByIdAsc(InvestorContract contract);
}
