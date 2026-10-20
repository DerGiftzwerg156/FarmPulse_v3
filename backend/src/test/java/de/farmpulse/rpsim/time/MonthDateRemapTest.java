package de.farmpulse.rpsim.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import de.farmpulse.rpsim.authority.DirectPaymentService;
import de.farmpulse.rpsim.authority.InvestmentGrantService;
import de.farmpulse.rpsim.contract.LivestockService;
import de.farmpulse.rpsim.cooperative.CooperativeService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.CoopShareNotice;
import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.InvestmentGrant;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.farmwork.WinterServiceService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.CoopShareNoticeRepository;
import de.farmpulse.rpsim.repository.DirectPaymentApplicationRepository;
import de.farmpulse.rpsim.repository.InvestmentGrantRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logic review LR-M5 (T-08): dates planned in months keep their month when "days per period" changes; dates in game
 * days stay. Here 1 -> 3 days per period: month 5 starts at day 5 before and at day 15 after.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MonthDateRemapTest {

    static final long DAY = GameTime.MS_PER_DAY;
    static final GameTime.Anchor BEFORE = new GameTime.Anchor(0, 0, 1, 1);
    static final GameTime.Anchor AFTER = new GameTime.Anchor(0, 0, 3, 1);

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired LivestockService livestock;
    @Autowired InvestmentGrantService grants;
    @Autowired CooperativeService cooperative;
    @Autowired DirectPaymentService directPayment;
    @Autowired WinterServiceService winterService;
    @Autowired ServiceCaseRepository cases;
    @Autowired InvestmentGrantRepository grantRepo;
    @Autowired CoopShareNoticeRepository coopNotices;
    @Autowired DirectPaymentApplicationRepository applications;
    @Autowired ContractRepository contracts;

    Savegame sg;
    CalendarChangedEvent change;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000);
        change = new CalendarChangedEvent(sg.getId(), BEFORE, AFTER);
    }

    private ServiceCase livestockCase(CaseStatus status, long deadline) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.LIVESTOCK_OFFER);
        sc.setStatus(status);
        sc.setGameTime(0);
        sc.setCreatedAt(Instant.now());
        sc.setDeadlineGameTime(deadline);
        return cases.save(sc);
    }

    @Test
    void activeEmployeesKeepTheMonthOfTheirApprenticeshipAndContractEnd() {
        var c = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Jonas Kramer");
        Employee e = hiring.createEmployee(sg, c, JobRole.MACHINE_OPERATOR, 60, 2400);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setApprenticeshipEndsAtGameTime(5 * DAY);
        e.setContractEndsAtGameTime(7 * DAY);
        hiring.onCalendarChanged(change);
        assertThat(e.getApprenticeshipEndsAtGameTime()).isEqualTo(15 * DAY);
        assertThat(e.getContractEndsAtGameTime()).isEqualTo(21 * DAY);
    }

    @Test
    void theTradersDealDeadlineMovesButTheAnswerDeadlineStays() {
        ServiceCase deal = livestockCase(CaseStatus.IN_PROGRESS, 5 * DAY);
        ServiceCase offer = livestockCase(CaseStatus.AWAITING_PLAYER, 5 * DAY);
        livestock.onCalendarChanged(change);
        assertThat(deal.getDeadlineGameTime()).isEqualTo(15 * DAY);
        assertThat(offer.getDeadlineGameTime()).isEqualTo(5 * DAY);
    }

    @Test
    void grantPurchaseTimeAndBindingKeepTheirMonth() {
        InvestmentGrant approved = grant(InvestmentGrant.APPROVED);
        approved.setPurchaseDeadlineGameTime(5 * DAY);
        InvestmentGrant paid = grant(InvestmentGrant.PAID);
        paid.setBindingEndsGameTime(6 * DAY);
        grants.onCalendarChanged(change);
        assertThat(approved.getPurchaseDeadlineGameTime()).isEqualTo(15 * DAY);
        assertThat(approved.getApprovalDueGameTime()).as("processing time in game days").isEqualTo(2 * DAY);
        assertThat(paid.getBindingEndsGameTime()).isEqualTo(18 * DAY);
    }

    private InvestmentGrant grant(String status) {
        InvestmentGrant g = new InvestmentGrant();
        g.setSavegame(sg);
        g.setKind(InvestmentGrant.MACHINE);
        g.setStatus(status);
        g.setPlannedSum(10_000);
        g.setAppliedGameTime(0);
        g.setApprovalDueGameTime(2 * DAY);
        return grantRepo.save(g);
    }

    @Test
    void anOpenCoopNoticeKeepsItsMonth() {
        CoopShareNotice open = new CoopShareNotice();
        open.setSavegame(sg);
        open.setShares(2);
        open.setNoticedGameTime(0);
        open.setDueGameTime(5 * DAY);
        coopNotices.save(open);
        CoopShareNotice paid = new CoopShareNotice();
        paid.setSavegame(sg);
        paid.setShares(1);
        paid.setNoticedGameTime(0);
        paid.setDueGameTime(4 * DAY);
        paid.setPaidGameTime(4 * DAY);
        coopNotices.save(paid);
        cooperative.onCalendarChanged(change);
        assertThat(open.getDueGameTime()).isEqualTo(15 * DAY);
        assertThat(paid.getDueGameTime()).isEqualTo(4 * DAY);
    }

    @Test
    void theAreaPaymentDeadlineKeepsItsMonth() {
        DirectPaymentApplication a = new DirectPaymentApplication();
        a.setSavegame(sg);
        a.setCropYear(1);
        a.setStatus(DirectPaymentApplication.OPEN);
        a.setOpenedGameTime(0);
        a.setDeadlineGameTime(3 * DAY);
        applications.save(a);
        directPayment.onCalendarChanged(change);
        assertThat(a.getDeadlineGameTime()).isEqualTo(9 * DAY);
    }

    @Test
    void theWinterServiceOfferRunsUntilTheSameMonthStart() {
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.WINTER_SERVICE);
        c.setStatus(ContractStatus.OFFERED);
        c.setCreatedAt(Instant.now());
        c.setOfferExpiresAtGameTime(9 * DAY);
        contracts.save(c);
        winterService.onCalendarChanged(change);
        assertThat(c.getOfferExpiresAtGameTime()).isEqualTo(27 * DAY);
    }
}
