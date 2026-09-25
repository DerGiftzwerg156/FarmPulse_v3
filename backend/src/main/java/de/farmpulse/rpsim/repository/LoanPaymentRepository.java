package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.LoanPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPaymentRepository extends JpaRepository<LoanPayment, Long> {
}
