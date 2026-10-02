package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.CollateralStatus;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanCollateral;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanCollateralRepository extends JpaRepository<LoanCollateral, Long> {

    List<LoanCollateral> findBySavegameAndStatusInOrderByIdAsc(Savegame savegame, Collection<CollateralStatus> statuses);

    List<LoanCollateral> findBySavegameAndFarmlandIdAndStatusIn(Savegame savegame, int farmlandId,
                                                               Collection<CollateralStatus> statuses);

    List<LoanCollateral> findByApplicationOrderByIdAsc(CreditApplication application);

    List<LoanCollateral> findByLoanOrderByIdAsc(Loan loan);

    List<LoanCollateral> findByLoanAndStatus(Loan loan, CollateralStatus status);

    List<LoanCollateral> findByReleaseInstructionId(String releaseInstructionId);
}
