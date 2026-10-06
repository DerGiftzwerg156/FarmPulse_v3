package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.InvestmentGrantObject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentGrantObjectRepository extends JpaRepository<InvestmentGrantObject, Long> {

    List<InvestmentGrantObject> findByGrantIdOrderByIdAsc(Long grantId);
}
