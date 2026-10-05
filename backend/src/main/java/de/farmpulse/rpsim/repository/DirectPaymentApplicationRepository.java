package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectPaymentApplicationRepository extends JpaRepository<DirectPaymentApplication, Long> {

    List<DirectPaymentApplication> findBySavegameOrderByIdDesc(Savegame savegame);
}
