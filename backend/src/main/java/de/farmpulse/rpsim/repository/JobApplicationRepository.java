package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobPosting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {

    List<JobApplication> findByPostingOrderByIdAsc(JobPosting posting);

    /** Roadmap V3.1 R31-A5: whether a former seasonal worker applied again since the given game time. */
    boolean existsByReturningEmployeeIdAndCreatedAtGameTimeGreaterThanEqual(Long returningEmployeeId, long gameTime);
}
