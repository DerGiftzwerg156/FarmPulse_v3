package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPayment;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPaymentRepository extends JpaRepository<LoanPayment, Long> {

    List<LoanPayment> findByLoanOrderByGameTimeAscIdAsc(Loan loan);

    long countBySavegameAndType(Savegame savegame, LoanPaymentType type);

    Optional<LoanPayment> findFirstByInstructionId(String instructionId);
}
