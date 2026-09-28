package de.farmpulse.rpsim.api;

import de.farmpulse.rpsim.api.Views.TaxAssessmentView;
import de.farmpulse.rpsim.api.Views.TaxOverviewView;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TaxYear;
import de.farmpulse.rpsim.family.FamilyService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.tax.TaxService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roadmap V2 R2-E: tax overview (E1) and the family field (E3). */
@RestController
public class RoleplayController {

    private final SavegameContext context;
    private final TaxService tax;
    private final FamilyService family;

    public RoleplayController(SavegameContext context, TaxService tax, FamilyService family) {
        this.context = context;
        this.tax = tax;
        this.family = family;
    }

    @GetMapping("/api/tax")
    @Transactional(readOnly = true)
    public TaxOverviewView tax() {
        Savegame sg = context.requireActive();
        TaxService.Overview o = tax.overview(sg);
        return new TaxOverviewView(o.currentYear(), o.incomeSoFar(), o.expenseSoFar(), o.estimatedTax(), o.ratePercent(),
                o.allowance(), o.nextPrepayment(), o.nextPrepaymentPeriod(), assessment(o.lastAssessment()),
                o.advisorActive(), o.journalAvailable(), tax.openBills(sg).size());
    }

    static TaxAssessmentView assessment(TaxYear y) {
        return y == null ? null : new TaxAssessmentView(y.getTaxYear(), y.getMonths(), y.getOperatingIncome(),
                y.getOperatingExpense(), y.getDepreciation(), y.getInterest(), y.getProfit(), y.getAllowance(),
                y.getTaxable(), Math.round(y.getTaxRate() * 1000) / 10.0, y.getAdvisorReduction(), y.getTax(),
                y.getPrepayments(), y.getBalance(), y.getAuditStatus());
    }

    /** The player marks an own field as family field ("the field at the brook"). */
    @PutMapping("/api/farmlands/{id}/family-field")
    @Transactional
    public void markFamilyField(@PathVariable int id) {
        family.markFamilyField(context.requireActive(), id);
    }

    @DeleteMapping("/api/family-field")
    @Transactional
    public void clearFamilyField() {
        family.markFamilyField(context.requireActive(), null);
    }
}
