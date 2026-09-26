package de.farmpulse.rpsim.credit;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.LoanPaymentRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.stereotype.Service;

/**
 * Collects the live data basis of the credit check (functional concept "Datenbasis der Bonitätsprüfung") and
 * evaluates {@link CreditFormula}. Pure Java, no AI call. The raw score never leaves the fact layer.
 */
@Service
public class CreditScoringService {

    private final FactsService facts;
    private final FactsSnapshotRepository snapshots;
    private final LoanRepository loans;
    private final LoanPaymentRepository payments;
    private final LiquidityService liquidity;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final CreditConfigResolver configs;
    private final GameTime gameTime;

    public CreditScoringService(FactsService facts, FactsSnapshotRepository snapshots, LoanRepository loans,
                                LoanPaymentRepository payments, LiquidityService liquidity, CharacterLookup lookup,
                                TrustScoreService trust, CreditConfigResolver configs, GameTime gameTime) {
        this.facts = facts;
        this.snapshots = snapshots;
        this.loans = loans;
        this.payments = payments;
        this.liquidity = liquidity;
        this.lookup = lookup;
        this.trust = trust;
        this.configs = configs;
        this.gameTime = gameTime;
    }

    public CreditFormula.Result score(Savegame sg, long amount, int termMonths, double interestRate) {
        return CreditFormula.evaluate(inputs(sg, amount, termMonths, interestRate), configs.forSavegame(sg));
    }

    public CreditFormula.Inputs inputs(Savegame sg, long amount, int termMonths, double interestRate) {
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        FarmFacts f = facts.latest(sg).orElse(null);
        double assets = f == null ? 0 : facts.totalAssetValue(f);
        List<Loan> active = new java.util.ArrayList<>(loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE));
        // T-03: an uncollected call-back is still debt
        active.addAll(loans.findBySavegameAndStatus(sg, LoanStatus.DEFAULTED));
        double loanDebt = active.stream().mapToDouble(Loan::getRemainingAmount).sum();
        double vanilla = f == null ? 0 : facts.vanillaLoanRemaining(f);
        double existingInstallments = active.stream().mapToDouble(Loan::getMonthlyInstallment).sum();
        // T-04: running leasing costs are an obligation like an installment (the game pays them from the balance, so
        // the operating cash flow below already contains them)
        existingInstallments += f == null ? 0 : FactsService.leasingCostPerMonth(f);
        double newInstallment = CreditFormula.monthlyInstallment(amount, interestRate, termMonths);
        double balance = f == null ? 0 : f.liquidity().balance();

        long now = sg.getCurrentGameTime();
        long windowStart = now - GameTime.days(cfg.getCashflowWindowDays());
        List<FactsSnapshot> window = snapshots.findBySavegameAndGameTimeBetweenOrderByGameTimeAscIdAsc(sg, windowStart, now);
        boolean hasHistory = false;
        double monthlyCashflow = 0;
        if (window.size() >= 2) {
            FactsSnapshot first = window.get(0);
            FactsSnapshot last = window.get(window.size() - 1);
            long span = last.getGameTime() - first.getGameTime();
            if (GameTime.toDays(span) >= cfg.getCashflowMinHistoryDays()) {
                hasHistory = true;
                long delta = last.getBalance() - first.getBalance();
                // operating cash flow = balance change without financing/one-off bookings (installments added back)
                long nonOperating = liquidity.nonOperatingApplied(sg, first.getGameTime(), last.getGameTime());
                monthlyCashflow = (delta - nonOperating) / (span / (double) gameTime.msPerMonth());
            }
        }
        double history = CreditFormula.paymentHistoryScore(
                payments.countBySavegameAndType(sg, LoanPaymentType.INSTALLMENT),
                payments.countBySavegameAndType(sg, LoanPaymentType.MISSED), cfg);
        double trustScore = lookup.bank(sg).map(trust::getCurrentTrust).orElse(0.0);
        return new CreditFormula.Inputs(monthlyCashflow, hasHistory, existingInstallments, newInstallment, assets,
                loanDebt + vanilla, balance, amount, history, trustScore);
    }

    /** Monthly operating cash flow trend (reused by the village-life congratulation trigger). */
    public double monthlyCashflow(Savegame sg, long from, long to) {
        List<FactsSnapshot> window = snapshots.findBySavegameAndGameTimeBetweenOrderByGameTimeAscIdAsc(sg, from, to);
        if (window.size() < 2) {
            return 0;
        }
        FactsSnapshot first = window.get(0);
        FactsSnapshot last = window.get(window.size() - 1);
        long span = last.getGameTime() - first.getGameTime();
        if (span <= 0) {
            return 0;
        }
        long nonOperating = liquidity.nonOperatingApplied(sg, first.getGameTime(), last.getGameTime());
        return (last.getBalance() - first.getBalance() - nonOperating) / (span / (double) gameTime.msPerMonth());
    }
}
