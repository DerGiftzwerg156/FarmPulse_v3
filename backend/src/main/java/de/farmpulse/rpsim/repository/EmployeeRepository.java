package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findBySavegameAndStatus(Savegame savegame, EmployeeStatus status);

    List<Employee> findBySavegameAndStatusAndJobRole(Savegame savegame, EmployeeStatus status, JobRole role);

    List<Employee> findBySavegameOrderByIdAsc(Savegame savegame);

    Optional<Employee> findByCharacter(Character character);
}
