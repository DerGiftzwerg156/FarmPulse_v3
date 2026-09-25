package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    List<JobPosting> findBySavegameOrderByIdDesc(Savegame savegame);
}
