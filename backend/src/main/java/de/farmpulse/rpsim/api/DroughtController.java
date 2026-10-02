package de.farmpulse.rpsim.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.domain.Drought;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.drought.DroughtService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-W: drought status for the insurance app - rain of the last months, the current series, the quote of
 * the weather-index insurance and the declared droughts. The drought insurance is requested with
 * {@code POST /api/insurance/offer} (level {@code DROUGHT_INDEX}), the drought aid runs as service case
 * {@code DROUGHT_AID} ({@code /api/cases}).
 */
@RestController
public class DroughtController {

    /** One game month: rain and observed time in percent, rating DRY / WET / UNKNOWN / OUTSIDE / RUNNING. */
    public record MonthView(long monthIndex, String month, Double rainPercent, double observedPercent, String rating) {
    }

    /** Current quote of the drought insurance for the own fields without leased ones. */
    public record DroughtQuoteView(double hectares, double premiumPerHectare, long monthlyPremium, double payoutPerHectare,
                                   long payout) {
    }

    public record DroughtView(Long id, String firstMonth, String lastMonth, long dryMonths, long declaredGameTime,
                              List<String> crops, int priceEvents, String insuranceResult, Double insuredHectares,
                              Long insurancePayout, Double aidHectares, Long aidCaseId) {
    }

    public record DroughtStatusView(boolean enabled, List<String> growthMonths, int minPeriods, double maxRainPercent,
                                    double minObservedPercent, int dryMonths, String seriesStartMonth,
                                    boolean seriesDeclared, List<MonthView> months, DroughtQuoteView quote,
                                    double aidPerHectare, double aidDeductionPercent, List<DroughtView> droughts) {
    }

    static final int MONTHS_SHOWN = 6;

    private final SavegameContext context;
    private final DroughtService drought;
    private final InsuranceService insurance;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public DroughtController(SavegameContext context, DroughtService drought, InsuranceService insurance,
                             GameTime gameTime, RpsimProperties props) {
        this.context = context;
        this.drought = drought;
        this.insurance = insurance;
        this.gameTime = gameTime;
        this.props = props;
    }

    @GetMapping("/api/drought")
    @Transactional(readOnly = true)
    public DroughtStatusView status() {
        Savegame sg = context.requireActive();
        RpsimProperties.Drought cfg = props.getFormulas().getDrought();
        RpsimProperties.Insurance ins = props.getFormulas().getInsurance();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long current = anchor.monthIndex(sg.getCurrentGameTime());
        List<MonthView> months = new ArrayList<>();
        for (long m = current - MONTHS_SHOWN + 1; m <= current; m++) {
            DroughtService.MonthRain r = drought.rain(sg, m);
            months.add(new MonthView(m, month(anchor, m), r.rainShare() == null ? null : percent(r.rainShare()),
                    percent(r.observedShare()), m == current ? "RUNNING" : drought.rate(sg, m).name()));
        }
        double ha = insurance.droughtArea(sg);
        DroughtQuoteView quote = new DroughtQuoteView(Math.round(ha * 100) / 100.0, ins.getDroughtPremiumPerHectare(),
                insurance.droughtPremium(ha), ins.getDroughtPayoutPerHectare(), insurance.droughtPayout(ha));
        return new DroughtStatusView(cfg.isEnabled(), cfg.getPeriods().stream().map(CalendarText::month).toList(),
                cfg.getMinPeriods(), percent(cfg.getMaxRainShare()), percent(cfg.getMinObservedShare()),
                sg.getDroughtDryMonths(),
                sg.getDroughtSeriesStartMonth() == null ? null : month(anchor, sg.getDroughtSeriesStartMonth()),
                sg.isDroughtSeriesDeclared(), months, quote, cfg.getAidPerHectare(),
                percent(cfg.getAidInsuranceDeduction()),
                drought.list(sg).stream().map(d -> view(anchor, d)).toList());
    }

    static DroughtView view(GameTime.Anchor anchor, Drought d) {
        return new DroughtView(d.getId(), month(anchor, d.getFirstMonthIndex()), month(anchor, d.getLastMonthIndex()),
                d.getLastMonthIndex() - d.getFirstMonthIndex() + 1, d.getDeclaredGameTime(),
                d.getCrops() == null ? List.of() : Arrays.asList(d.getCrops().split(",")), d.getPriceEvents(),
                d.getInsuranceResult(), d.getInsuredHectares(), d.getInsurancePayout(), d.getAidHectares(),
                d.getAidCaseId());
    }

    private static String month(GameTime.Anchor anchor, long monthIndex) {
        return CalendarText.month(anchor.periodOf(monthIndex));
    }

    private static double percent(double share) {
        return Math.round(share * 1000) / 10.0;
    }
}
