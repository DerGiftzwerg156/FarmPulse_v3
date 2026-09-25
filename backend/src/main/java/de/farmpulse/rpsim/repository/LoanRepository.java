package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findBySavegameOrderByIdAsc(Savegame savegame);

    List<Loan> findBySavegameAndStatus(Savegame savegame, LoanStatus status);

    boolean existsBySavegameAndBlocksNewCreditTrue(Savegame savegame);
}
