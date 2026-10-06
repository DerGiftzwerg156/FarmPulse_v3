package de.farmpulse.rpsim.authority;

import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B5: annual fee of the agricultural social insurance (owner decisions 2026-10-05 in QUESTIONS.md).
 * At the start of bill-period (April) the Berufsgenossenschaft (role SOCIAL_INSURANCE, created at its first occasion)
 * sends the bill: base-fee + fee-per-ha x hectares of the own fields (leased-in included) + fee-per-employee x active
 * employees (seasonal workers included). Paid like a tax bill under Ämter (AuthorityBillService), booked as
 * SOCIAL_INSURANCE.
 */
@Service
public class SocialInsuranceService {

    private final SavegameRepository savegames;
    private final EmployeeRepository employees;
    private final FactsService facts;
    private final AuthorityBillService bills;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public SocialInsuranceService(SavegameRepository savegames, EmployeeRepository employees, FactsService facts,
                                  AuthorityBillService bills, ServiceRoleService roles, NarrationRequestService narration,
                                  RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.employees = employees;
        this.facts = facts;
        this.bills = bills;
        this.roles = roles;
        this.narration = narration;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.SocialInsurance cfg() {
        return props.getFormulas().getSocialInsurance();
    }

    /** Fee = base-fee + fee-per-ha x hectares + fee-per-employee x employees. */
    public static long fee(double hectares, int employees, RpsimProperties.SocialInsurance cfg) {
        return cfg.getBaseFee() + Math.round(hectares * cfg.getFeePerHa()) + cfg.getFeePerEmployee() * employees;
    }

    /** Hectares of the own fields (field export, otherwise the farmland of the assets). */
    static double hectares(FarmFacts f) {
        if (f == null) {
            return 0;
        }
        if (f.fields() != null) {
            return f.fields().stream().filter(x -> x != null && x.hectares() != null).mapToDouble(BridgeDtos.Field::hectares).sum();
        }
        if (f.assets() != null && f.assets().farmland() != null) {
            return f.assets().farmland().stream().filter(x -> x != null && x.hectares() != null)
                    .mapToDouble(x -> x.hectares()).sum();
        }
        return 0;
    }

    @EventListener
    @Order(82)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (cfg().isEnabled() && gameTime.periodOfYear(sg, sg.getCurrentGameTime()) == cfg().getBillPeriod()) {
            bill(sg);
        }
    }

    /** The bill of the running FS25 year (once per year). */
    Optional<ServiceCase> bill(Savegame sg) {
        long now = sg.getCurrentGameTime();
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = f == null || f.calendar() == null ? null : f.calendar().year();
        long month = gameTime.monthIndex(sg, now);
        boolean sent = bills.bills(sg).stream().anyMatch(b -> b.getKind() == CaseKind.SOCIAL_INSURANCE_BILL
                && (year != null ? year.equals(b.getQuantity()) : gameTime.monthIndex(sg, b.getGameTime()) == month));
        if (sent) {
            return Optional.empty();
        }
        double ha = hectares(f);
        int staff = employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE).size();
        long amount = fee(ha, staff, cfg());
        ServiceCase b = bills.create(sg, CaseKind.SOCIAL_INSURANCE_BILL, roles.ensure(sg, CharacterRole.SOCIAL_INSURANCE),
                "Beitrag Berufsgenossenschaft" + (year == null ? "" : " " + year), amount, "SOCIAL_INSURANCE");
        b.setQuantity(year);
        b.setHectares(Math.round(ha * 100) / 100.0);
        b.setBaselineCount(staff);
        narration.request(sg, NarrationEventType.SOCIAL_INSURANCE_BILL).from(b.getCharacter())
                .facts(NarrationFacts.builder().put("amount", amount).put("baseFee", cfg().getBaseFee())
                        .put("hectares", Math.round(ha * 10) / 10.0).put("feePerHa", cfg().getFeePerHa())
                        .put("employees", staff).put("feePerEmployee", cfg().getFeePerEmployee())
                        .put("paymentDays", Math.round(props.getFormulas().getTax().getPaymentDays())).build())
                .category(CommunicationCategory.CONTRACT).related(AuthorityBillService.RELATED, b.getId())
                .formLink("/aemter?case=" + b.getId()).submit();
        return Optional.of(b);
    }
}
