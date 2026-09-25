package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.CreditApplicationRequest;
import de.farmpulse.rpsim.api.Requests.DeferralRequest;
import de.farmpulse.rpsim.api.Views.CreditApplicationView;
import de.farmpulse.rpsim.api.Views.DeferralView;
import de.farmpulse.rpsim.api.Views.LoanView;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreditController {

    private final SavegameContext context;
    private final CreditApplicationService applications;
    private final LoanService loans;
    private final ApiMapper mapper;

    public CreditController(SavegameContext context, CreditApplicationService applications, LoanService loans,
                            ApiMapper mapper) {
        this.context = context;
        this.applications = applications;
        this.loans = loans;
        this.mapper = mapper;
    }

    /** Form: amount, purpose, term. The decision stays hidden ("in Bearbeitung") until decisionVisibleAtGameTime. */
    @PostMapping("/api/credit-applications")
    @Transactional
    public CreditApplicationView apply(@Valid @RequestBody CreditApplicationRequest r) {
        return mapper.application(applications.submit(context.requireActive(), r.amount(), r.purpose(), r.termMonths()));
    }

    @GetMapping("/api/credit-applications")
    @Transactional(readOnly = true)
    public List<CreditApplicationView> applications() {
        return applications.list(context.requireActive()).stream().map(mapper::application).toList();
    }

    @PostMapping("/api/credit-applications/{id}/accept-counter")
    @Transactional
    public CreditApplicationView acceptCounter(@PathVariable Long id) {
        return mapper.application(applications.acceptCounterOffer(context.requireActive(), id));
    }

    @PostMapping("/api/credit-applications/{id}/decline-counter")
    @Transactional
    public CreditApplicationView declineCounter(@PathVariable Long id) {
        return mapper.application(applications.declineCounterOffer(context.requireActive(), id));
    }

    @GetMapping("/api/loans")
    @Transactional(readOnly = true)
    public List<LoanView> loans() {
        return loans.list(context.requireActive()).stream().map(mapper::loan).toList();
    }

    @PostMapping("/api/loans/{id}/stundung")
    @Transactional
    public DeferralView deferral(@PathVariable Long id, @Valid @RequestBody(required = false) DeferralRequest r) {
        LoanService.DeferralResult d = loans.requestDeferral(context.requireActive(), id, r == null ? null : r.message());
        return new DeferralView(d.granted(), d.reasonCategory());
    }
}
