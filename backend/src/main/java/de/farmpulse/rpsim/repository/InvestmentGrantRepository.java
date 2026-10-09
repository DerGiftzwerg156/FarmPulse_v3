package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.InvestmentGrant;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentGrantRepository extends JpaRepository<InvestmentGrant, Long> {

    List<InvestmentGrant> findBySavegameOrderByIdDesc(Savegame savegame);
}
