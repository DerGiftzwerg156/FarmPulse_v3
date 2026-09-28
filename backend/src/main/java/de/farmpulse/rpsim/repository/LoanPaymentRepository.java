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

    /** Roadmap V2 R2-E1: installments of a tax year (their interest part reduces the taxable profit). */
    List<LoanPayment> findBySavegameAndTypeAndGameTimeGreaterThanEqualAndGameTimeLessThan(Savegame savegame,
                                                                                          LoanPaymentType type, long from,
                                                                                          long to);
}
