package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.CaseView;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.farmwork.ContractorWorkService;
import de.farmpulse.rpsim.farmwork.MachineLoanService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3.1 section A "Arbeit auf dem Hof": R31-A1 contractor work on own fields (Flurkarte → own field →
 * "Lohnunternehmer beauftragen"), R31-A2 borrowed machines of neighbours ("Maschine leihen") and demos of the workshop
 * ("Vorführung anfragen"; a demo the workshop offers is accepted via {@code /api/cases/{id}/accept}).
 */
@RestController
public class FarmWorkController {

    /** One work of the form; {@code reason} = why it is not possible (null = possible). */
    public record WorkOptionView(String work, long price, Long harvestLiters, String fillType, String reason) {
    }

    /**
     * {@code selected} = the ticked works the options were checked with; {@code doneByGameTime} = the work is done by
     * then (end of the next game day); {@code openOrders} = the open order of the field, one case per work.
     */
    public record ContractorQuoteView(int farmlandId, String fieldName, double hectares, String phase,
                                      List<WorkOptionView> options, List<String> selected, List<String> fruitTypes,
                                      int maxWorks, long doneByGameTime, List<CaseView> openOrders) {
    }

    /** {@code works} = 1 to maxWorks works of the field done on the same day; {@code work} = a single work (older UI). */
    public record ContractorOrderRequest(@NotNull Integer farmlandId, String work, List<String> works,
                                         String fruitType) {
        List<String> all() {
            if (works != null && !works.isEmpty()) {
                return works;
            }
            return work == null || work.isBlank() ? List.of() : List.of(work);
        }
    }

    /** R31-A2: a machine to choose from; {@code dailyRent} 0 for a demo. */
    public record LoanChoiceView(String storeXmlFilename, String name, String categoryName, long listPrice, long dailyRent) {
    }

    public record LoanChoicesView(int daysMin, int daysMax, List<LoanChoiceView> choices) {
    }

    /** R31-A2: a borrowed (LOAN) or demo (DEMO) machine; {@code lender} = neighbour or workshop. */
    public record LoanView(Long id, String kind, String status, Views.CharacterRef lender, String vehicleName,
                           String categoryName, long listPrice, int days, long dailyRent, String vehicleId,
                           Long deliveredGameTime, Long endsGameTime, int rentDaysBooked, int lateDays, Long compensation,
                           String endReason, Long vehicleDealId) {
    }

    public record BorrowRequest(@NotBlank String storeXmlFilename, @NotNull @Positive Integer days) {
    }

    public record DemoRequest(@NotBlank String storeXmlFilename) {
    }

    private final SavegameContext context;
    private final ContractorWorkService contractor;
    private final MachineLoanService loans;
    private final de.farmpulse.rpsim.config.RpsimProperties props;
    private final ApiMapper mapper;

    public FarmWorkController(SavegameContext context, ContractorWorkService contractor, MachineLoanService loans,
                              de.farmpulse.rpsim.config.RpsimProperties props, ApiMapper mapper) {
        this.context = context;
        this.contractor = contractor;
        this.loans = loans;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * R31-A1: the works the contractor offers for an own field, with price and when it is done; {@code works} = the
     * ticked works, the other works are checked as an addition to them.
     */
    @GetMapping("/api/contractor-work/fields/{farmlandId}")
    @Transactional
    public ContractorQuoteView quote(@PathVariable int farmlandId,
                                     @RequestParam(name = "works", required = false) List<String> works) {
        ContractorWorkService.Quote q = contractor.quote(context.requireActive(), farmlandId,
                works == null ? List.of() : works);
        return new ContractorQuoteView(q.farmlandId(), q.fieldName(), q.hectares(), q.phase().name(),
                q.options().stream().map(o -> new WorkOptionView(o.work(), o.price(), o.harvestLiters(), o.fillType(),
                        o.reason())).toList(), q.selected(), q.fruitTypes(), q.maxWorks(), q.doneByGameTime(),
                q.openOrders().stream().map(mapper::serviceCase).toList());
    }

    /** R31-A1: all contractor jobs (open and closed), newest first. */
    @GetMapping("/api/contractor-work")
    @Transactional
    public List<CaseView> orders() {
        return contractor.orders(context.requireActive()).stream().map(mapper::serviceCase).toList();
    }

    /** R31-A1: order 1 to 3 works at once; one case per work, all done by {@code deadlineGameTime}. */
    @PostMapping("/api/contractor-work")
    @Transactional
    public List<CaseView> order(@Valid @RequestBody ContractorOrderRequest r) {
        return contractor.order(context.requireActive(), r.farmlandId(), r.all(), r.fruitType()).stream()
                .map(mapper::serviceCase).toList();
    }

    // ------------------------------------------------------------------------------------------ R31-A2

    private LoanView view(MachineLoan l) {
        return new LoanView(l.getId(), l.getKind(), l.getStatus(), mapper.ref(l.getCharacter()), l.getVehicleName(),
                l.getCategoryName(), l.getListPrice(), l.getDays(), l.getDailyRent(), l.getVehicleId(),
                l.getDeliveredGameTime(), l.getEndsGameTime(), l.getRentDaysBooked(), l.getLateDays(), l.getCompensation(),
                l.getEndReason(), l.getVehicleDealId());
    }

    private static List<LoanChoiceView> choices(List<MachineLoanService.Choice> list) {
        return list.stream().map(c -> new LoanChoiceView(c.storeXmlFilename(), c.name(), c.categoryName(), c.listPrice(),
                c.dailyRent())).toList();
    }

    /** R31-A2: all borrowed and demo machines, newest first (the running one first). */
    @GetMapping("/api/machine-loans")
    @Transactional
    public List<LoanView> machineLoans() {
        return loans.list(context.requireActive()).stream().map(this::view).toList();
    }

    /** R31-A2: "Maschine leihen" - the machines the neighbour lends today, with the rent per game day. */
    @GetMapping("/api/machine-loans/neighbors/{id}")
    @Transactional
    public LoanChoicesView loanChoices(@PathVariable Long id) {
        var cfg = props.getFormulas().getMachineLoan();
        return new LoanChoicesView(cfg.getDaysMin(), cfg.getDaysMax(), choices(loans.loanChoices(context.requireActive(), id)));
    }

    @PostMapping("/api/machine-loans/neighbors/{id}")
    @Transactional
    public LoanView borrow(@PathVariable Long id, @Valid @RequestBody BorrowRequest r) {
        return view(loans.borrow(context.requireActive(), id, r.storeXmlFilename(), r.days()));
    }

    /** R31-A2: "Vorführung anfragen" - the machines the workshop shows today (free, 1-2 game days). */
    @GetMapping("/api/machine-loans/demo")
    @Transactional
    public LoanChoicesView demoChoices() {
        var cfg = props.getFormulas().getMachineLoan();
        return new LoanChoicesView(cfg.getDemoDaysMin(), cfg.getDemoDaysMax(), choices(loans.demoChoices(context.requireActive())));
    }

    @PostMapping("/api/machine-loans/demo")
    @Transactional
    public LoanView requestDemo(@Valid @RequestBody DemoRequest r) {
        return view(loans.requestDemo(context.requireActive(), r.storeXmlFilename()));
    }
}
