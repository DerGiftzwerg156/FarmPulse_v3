package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorPaymentRepository extends JpaRepository<InvestorPayment, Long> {

    List<InvestorPayment> findByContractOrderByIdAsc(InvestorContract contract);

    List<InvestorPayment> findBySavegameOrderByIdAsc(Savegame savegame);

    List<InvestorPayment> findByClaimCaseId(Long claimCaseId);

    Optional<InvestorPayment> findByInstructionId(String instructionId);
}
