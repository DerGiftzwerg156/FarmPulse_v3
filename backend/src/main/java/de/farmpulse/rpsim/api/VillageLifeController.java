package de.farmpulse.rpsim.api;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.cooperative.CooperativeService;
import de.farmpulse.rpsim.domain.CoopShareNotice;
import de.farmpulse.rpsim.domain.DieselTheft;
import de.farmpulse.rpsim.domain.FarmHolidayMonth;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TankLock;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.theft.DieselTheftService;
import de.farmpulse.rpsim.villagelife.FarmHolidayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3.1 section D: farm holidays (D6) and cooperative shares (D7) as cards in "Handel", the tank lock in
 * "Werkstatt" and the theft module of the storm / hail insurance (D8). Regulars' table, school visits, the general
 * assembly, board meetings and crop damage claims run as service cases ({@code /api/cases}).
 */
@RestController
public class VillageLifeController {

    // ------------------------------------------------------------------------------------------ D6

    public record HolidayMonthView(long monthIndex, int period, long income, double seasonFactor, double reputationFactor,
                                   double animalFactor, boolean noise, boolean smell, boolean badReview, long gameTime) {
    }

    public record HolidayPreviewView(int period, double seasonFactor, double reputationFactor, double animalFactor,
                                     boolean noise, boolean smell, boolean badReview, long income) {
    }

    public record FarmHolidayView(boolean enabled, long setupCost, double baseIncomePerMonth, Long since,
                                  HolidayPreviewView preview, List<HolidayMonthView> months) {
    }

    // ------------------------------------------------------------------------------------------ D7

    public record CoopNoticeView(Long id, int shares, long noticedGameTime, long dueGameTime, Long paidGameTime) {
    }

    public record CooperativeView(boolean enabled, long sharePrice, int maxShares, int shares, int noticedShares,
                                  boolean board, int boardMissed, int boardMinShares, double dividendRatePercent,
                                  double dividendBonusPercent, boolean grainStore, int noticeMonths,
                                  List<CoopNoticeView> notices) {
    }

    public record SharesRequest(@NotNull @Positive Integer count) {
    }

    // ------------------------------------------------------------------------------------------ D8

    public record FuelVehicleView(String vehicleId, String name, Double fuelLiters, Double fuelCapacity, boolean tankLock) {
    }

    public record TheftView(Long id, String vehicleId, String vehicleName, String status, Long stolenLiters, Long damage,
                            Long insurancePayout, Long closedGameTime) {
    }

    public record DieselTheftView(boolean enabled, long tankLockPrice, long insurancePremiumPerMonth, long insuranceMinDamage,
                                  List<FuelVehicleView> vehicles, List<TheftView> thefts) {
    }

    public record TankLockRequest(@NotBlank String vehicleId) {
    }

    public record TheftCoverRequest(@NotNull Boolean enabled) {
    }

    private final SavegameContext context;
    private final FarmHolidayService holidays;
    private final CooperativeService cooperative;
    private final DieselTheftService thefts;
    private final FactsService facts;
    private final LoanedVehicles loaned;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public VillageLifeController(SavegameContext context, FarmHolidayService holidays, CooperativeService cooperative,
                                 DieselTheftService thefts, FactsService facts, LoanedVehicles loaned, ApiMapper mapper,
                                 RpsimProperties props) {
        this.context = context;
        this.holidays = holidays;
        this.cooperative = cooperative;
        this.thefts = thefts;
        this.facts = facts;
        this.loaned = loaned;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping("/api/farm-holiday")
    @Transactional(readOnly = true)
    public FarmHolidayView farmHoliday() {
        Savegame sg = context.requireActive();
        RpsimProperties.FarmHoliday c = props.getFormulas().getFarmHoliday();
        FarmHolidayService.MonthResult p = holidays.preview(sg);
        return new FarmHolidayView(c.isEnabled(), c.getSetupCost(), c.getBaseIncomePerMonth(), sg.getFarmHolidaySince(),
                new HolidayPreviewView(p.period(), p.seasonFactor(), p.reputationFactor(), p.animalFactor(), p.noise(),
                        p.smell(), p.badReview(), p.income()),
                holidays.history(sg).stream().map(VillageLifeController::view).toList());
    }

    private static HolidayMonthView view(FarmHolidayMonth m) {
        return new HolidayMonthView(m.getMonthIndex(), m.getPeriod(), m.getIncome(), m.getSeasonFactor(),
                m.getReputationFactor(), m.getAnimalFactor(), m.isNoise(), m.isSmell(), m.isBadReview(), m.getGameTime());
    }

    @PostMapping("/api/farm-holiday")
    @Transactional
    public FarmHolidayView setupFarmHoliday() {
        holidays.setup(context.requireActive());
        return farmHoliday();
    }

    @GetMapping("/api/cooperative")
    @Transactional
    public CooperativeView cooperative() {
        Savegame sg = context.requireActive();
        RpsimProperties.CoopShares c = props.getFormulas().getCoopShares();
        Integer ended = sg.getCalYear() == null ? null : sg.getCalYear() - 1;
        return new CooperativeView(c.isEnabled(), c.getSharePrice(), c.getMaxShares(), sg.getCoopShares(),
                cooperative.noticed(sg), sg.isCoopBoard(), sg.getCoopBoardMissed(),
                props.getFormulas().getCoopBoard().getMinShares(),
                Math.round(cooperative.dividendRate(sg, ended) * 1000) / 10.0,
                Math.round(sg.getCoopDividendBonus() * 1000) / 10.0, sg.isCoopGrainStore(), c.getNoticeMonths(),
                cooperative.notices(sg).stream().map(VillageLifeController::view).toList());
    }

    private static CoopNoticeView view(CoopShareNotice n) {
        return new CoopNoticeView(n.getId(), n.getShares(), n.getNoticedGameTime(), n.getDueGameTime(), n.getPaidGameTime());
    }

    @PostMapping("/api/cooperative/shares")
    @Transactional
    public CooperativeView buyShares(@Valid @RequestBody SharesRequest r) {
        cooperative.buy(context.requireActive(), r.count());
        return cooperative();
    }

    @PostMapping("/api/cooperative/notices")
    @Transactional
    public CooperativeView cancelShares(@Valid @RequestBody SharesRequest r) {
        cooperative.cancel(context.requireActive(), r.count());
        return cooperative();
    }

    @GetMapping("/api/diesel-theft")
    @Transactional(readOnly = true)
    public DieselTheftView dieselTheft() {
        Savegame sg = context.requireActive();
        RpsimProperties.DieselTheft c = props.getFormulas().getDieselTheft();
        Set<String> locked = thefts.locks(sg).stream().map(TankLock::getVehicleId).collect(Collectors.toSet());
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        List<FuelVehicleView> vehicles = f == null || f.assets() == null || f.assets().vehicles() == null ? List.of()
                : loaned.own(sg, f.assets().vehicles()).stream().filter(v -> v.uniqueId() != null)
                .map(v -> new FuelVehicleView(v.uniqueId(), v.name(), v.fuel() == null ? null : v.fuel().liters(),
                        v.fuel() == null ? null : v.fuel().capacity(), locked.contains(v.uniqueId()))).toList();
        return new DieselTheftView(c.isEnabled(), c.getTankLockPrice(), c.getInsurancePremiumPerMonth(),
                c.getInsuranceMinDamage(), vehicles, thefts.history(sg).stream()
                .filter(t -> DieselTheft.DONE.equals(t.getStatus()) && t.getStolenLiters() != null && t.getStolenLiters() > 0)
                .map(VillageLifeController::view).toList());
    }

    private static TheftView view(DieselTheft t) {
        return new TheftView(t.getId(), t.getVehicleId(), t.getVehicleName(), t.getStatus(), t.getStolenLiters(), t.getDamage(),
                t.getInsurancePayout(), t.getClosedGameTime());
    }

    @PostMapping("/api/tank-locks")
    @Transactional
    public DieselTheftView buyTankLock(@Valid @RequestBody TankLockRequest r) {
        thefts.buyTankLock(context.requireActive(), r.vehicleId());
        return dieselTheft();
    }

    @PostMapping("/api/contracts/{id}/theft-cover")
    @Transactional
    public Views.ContractView theftCover(@PathVariable Long id, @Valid @RequestBody TheftCoverRequest r) {
        return mapper.contract(thefts.theftCover(context.requireActive(), id, r.enabled()));
    }
}
