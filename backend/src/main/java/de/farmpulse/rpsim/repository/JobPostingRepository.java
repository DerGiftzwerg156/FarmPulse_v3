package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.JobPosting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
}
