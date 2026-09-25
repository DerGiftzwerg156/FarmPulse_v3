package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.SatisfactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SatisfactionEventRepository extends JpaRepository<SatisfactionEvent, Long> {

    List<SatisfactionEvent> findByEmployeeOrderByGameTimeAscIdAsc(Employee employee);
}
