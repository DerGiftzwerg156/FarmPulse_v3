package de.farmpulse.rpsim.villagelife;

import java.util.ArrayList;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmHolidayMonth;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.FarmHolidayMonthRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D6 (owner decisions 2026-10-05): farm holidays ("Ferien auf dem Hof", card in "Handel"). A one-off
 * setup ({@code setup-cost}, FARM_HOLIDAY_SETUP), then at every month start the guests of the month that ended pay
 * {@code GUEST_INCOME} = base × season × reputation × animals × (1 − cuts):
 * <ul>
 *   <li>season by the FS25 period of that month ({@code season-factors}, else {@code other-season-factor});</li>
 *   <li>village reputation tier ({@code reputation-factors});</li>
 *   <li>animals: a stable below {@code bad-health} × {@code bad-health-factor} and a bad review, else a stable from
 *   {@code good-health} × {@code good-health-factor}, no animals × 1;</li>
 *   <li>cuts: night work of helpers in that month (D4) {@code noise-cut}, slurry / manure spread (B3 detection) in the
 *   {@code smell-periods} {@code smell-cut}; a complaint brings a review mail of the guests.</li>
 * </ul>
 * The guests pay at every month start after the setup.
 */
@Service
public class FarmHolidayService {

    public static final String RELATED = "FARM_HOLIDAY";

    private final FarmHolidayMonthRepository months;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final NightWorkService nightWork;
    private final VillageReputationService reputation;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final de.farmpulse.rpsim.investor.InvestorLedger investors;

    public FarmHolidayService(FarmHolidayMonthRepository months, SavegameRepository savegames, FactsService facts,
                              LiquidityService liquidity, OutboxService outbox, NightWorkService nightWork,
                              VillageReputationService reputation, NarrationRequestService narration, DiaryService diary,
                              GameTime gameTime, RpsimProperties props,
                              de.farmpulse.rpsim.investor.InvestorLedger investors) {
        this.investors = investors;
        this.months = months;
        this.savegames = savegames;
        this.facts = facts;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.nightWork = nightWork;
        this.reputation = reputation;
        this.narration = narration;
        this.diary = diary;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.FarmHoliday cfg() {
        return props.getFormulas().getFarmHoliday();
    }

    /** "Ferienwohnung einrichten": once, paid as FARM_HOLIDAY_SETUP. */
    @Transactional
    public Savegame setup(Savegame sg) {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("FARM_HOLIDAY_DISABLED", "Ferien auf dem Hof sind abgeschaltet.");
        }
        if (sg.getFarmHolidaySince() != null) {
            throw new BusinessRuleException("FARM_HOLIDAY_EXISTS", "Die Ferienwohnung ist bereits eingerichtet.");
        }
        if (liquidity.available(sg) < cfg().getSetupCost()) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für die Einrichtung nicht.");
        }
        sg.setFarmHolidaySince(sg.getCurrentGameTime());
        outbox.money(sg, -cfg().getSetupCost(), MoneyReason.FARM_HOLIDAY_SETUP, "Einrichtung Ferienwohnung",
                new Related(RELATED, sg.getId()));
        diary.addAuto(sg, "OTHER", "Ferien auf dem Hof", "Die Ferienwohnung ist eingerichtet – ab dem nächsten Monat "
                + "kommen Gäste.", RELATED, sg.getId());
        return sg;
    }

    /** Result of one month - also the preview of the card. */
    /** {@code investor} = Roadmap V3.2 R32-I3 P3: the flat is kept free for an investor (no guests, no income). */
    public record MonthResult(int period, double seasonFactor, double reputationFactor, double animalFactor,
                              boolean noise, boolean smell, boolean badReview, long income, boolean investor) {
    }

    /** Income of the month {@code index} (period, reputation now, animals of the latest export, night work and smell). */
    public MonthResult compute(Savegame sg, long index) {
        long from = gameTime.monthStart(sg, index);
        long to = gameTime.monthStart(sg, index + 1);
        int period = gameTime.periodOfYear(sg, from);
        double season = cfg().getSeasonFactors().getOrDefault(period, cfg().getOtherSeasonFactor());
        double rep = cfg().getReputationFactors().getOrDefault(reputation.tier(sg).name(), 1.0);
        FarmFacts f = facts.latest(sg).orElse(null);
        List<BridgeDtos.Husbandry> stables = f == null || f.husbandries() == null ? List.of() : f.husbandries().stream()
                .filter(h -> h != null && h.health() != null).toList();
        boolean bad = stables.stream().anyMatch(h -> h.health() < cfg().getBadHealth());
        boolean good = stables.stream().anyMatch(h -> h.health() >= cfg().getGoodHealth());
        double animals = bad ? cfg().getBadHealthFactor() : good ? cfg().getGoodHealthFactor() : 1.0;
        boolean noise = nightWork.nightMs(sg, from - 1, to) > 0;
        Long spread = sg.getLastOrganicSpreadGameTime();
        boolean smell = cfg().getSmellPeriods().contains(period) && spread != null && spread >= from && spread <= to;
        double cuts = (noise ? cfg().getNoiseCut() : 0) + (smell ? cfg().getSmellCut() : 0);
        long income = Math.round(cfg().getBaseIncomePerMonth() * season * rep * animals * Math.max(0, 1 - cuts));
        if (investors.holidayFlatReserved(sg, index, period)) {
            return new MonthResult(period, season, rep, animals, false, false, false, 0, true);
        }
        return new MonthResult(period, season, rep, animals, noise, smell, bad, income, false);
    }

    @EventListener
    @Order(88)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Long since = sg.getFarmHolidaySince();
        if (!cfg().isEnabled() || since == null) {
            return;
        }
        long index = gameTime.monthIndex(sg, sg.getCurrentGameTime()) - 1;
        if (since >= sg.getCurrentGameTime() || months.existsBySavegameAndMonthIndex(sg, index)) {
            return;
        }
        MonthResult r = compute(sg, index);
        FarmHolidayMonth m = new FarmHolidayMonth();
        m.setSavegame(sg);
        m.setMonthIndex(index);
        m.setPeriod(r.period());
        m.setIncome(r.income());
        m.setSeasonFactor(r.seasonFactor());
        m.setReputationFactor(r.reputationFactor());
        m.setAnimalFactor(r.animalFactor());
        m.setNoise(r.noise());
        m.setSmell(r.smell());
        m.setBadReview(r.badReview());
        m.setGameTime(sg.getCurrentGameTime());
        months.save(m);
        if (r.income() > 0) {
            outbox.money(sg, r.income(), MoneyReason.GUEST_INCOME, "Feriengäste " + CalendarText.month(r.period()),
                    new Related(RELATED, m.getId()));
        }
        List<String> complaints = new ArrayList<>();
        if (r.noise()) {
            complaints.add("Nachts war es leider sehr laut – die Maschinen haben uns geweckt.");
        }
        if (r.smell()) {
            complaints.add("Es roch stark nach Gülle.");
        }
        if (r.badReview()) {
            complaints.add("Den Tieren im Stall ging es sichtbar nicht gut.");
        }
        if (!complaints.isEmpty()) {
            narration.request(sg, NarrationEventType.FARM_HOLIDAY_REVIEW)
                    .facts(NarrationFacts.builder().put("complaints", String.join(" ", complaints)).build())
                    .category(CommunicationCategory.TRADE).related(RELATED, m.getId()).submit();
        }
    }

    public List<FarmHolidayMonth> history(Savegame sg) {
        return months.findBySavegameOrderByIdDesc(sg);
    }

    /** Preview of the current month (for the card). */
    public MonthResult preview(Savegame sg) {
        return compute(sg, gameTime.monthIndex(sg, sg.getCurrentGameTime()));
    }
}
