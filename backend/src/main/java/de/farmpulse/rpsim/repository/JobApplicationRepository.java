package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {
}
