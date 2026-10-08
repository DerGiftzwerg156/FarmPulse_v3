package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.InvestorDelivery;
import de.farmpulse.rpsim.domain.InvestorObligation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorDeliveryRepository extends JpaRepository<InvestorDelivery, Long> {

    List<InvestorDelivery> findByObligationOrderByIdAsc(InvestorObligation obligation);

    Optional<InvestorDelivery> findByInstructionId(String instructionId);
}
