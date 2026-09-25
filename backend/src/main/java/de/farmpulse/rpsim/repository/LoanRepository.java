package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.Loan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepository extends JpaRepository<Loan, Long> {
}
