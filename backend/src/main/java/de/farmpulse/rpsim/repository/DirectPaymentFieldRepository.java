package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DirectPaymentField;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectPaymentFieldRepository extends JpaRepository<DirectPaymentField, Long> {

    List<DirectPaymentField> findByApplicationIdOrderByFarmlandIdAsc(Long applicationId);
}
