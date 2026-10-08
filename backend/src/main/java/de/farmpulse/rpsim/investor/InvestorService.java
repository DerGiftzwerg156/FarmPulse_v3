package de.farmpulse.rpsim.investor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.diary.PaymentDelayService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorDelivery;
import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.InvestorPeriod;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandBypassEvent;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.repository.InvestorContractRepository;
import de.farmpulse.rpsim.repository.InvestorDeliveryRepository;
import de.farmpulse.rpsim.repository.InvestorObligationRepository;
import de.farmpulse.rpsim.repository.InvestorPaymentRepository;
import de.farmpulse.rpsim.repository.InvestorPeriodRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.2 R32-I3 - R32-I5: running investor contracts (owner decisions 2026-10-08 in QUESTIONS.md).
 * <ul>
 *   <li>I3 Deliveries ("Liefern", partial deliveries allowed) without money: W1 / W2 {@code STORAGE_TRANSFER OUT}, W3
 *   {@code HUSBANDRY_TRANSFER} from the chosen stable, A1 {@code ANIMAL_TRANSFER OUT}; they count once the mod
 *   acknowledged them. P2 asks once per term year in a random month like a bulk buyer (G2 delivery at market price x
 *   0.9), P4 invites once per term year (accept by button). P1 needs the investor's consent for a field sale in the
 *   tool; a sale in the game menu (R2-D2) is a breach. P3 keeps the holiday flat free in July and August (no breach).
 *   R2 is paid at the FS25 year change, R1 after the farm report of each term year (no profit = no payment).</li>
 *   <li>I4 Check per billing period - month change (W2, W3, A2) or FS25 year change (W1 cumulative minimum, A1, A3, A4
 *   once after the first year); a reminder reminder-days-before-end before the end (mail + NOTIFICATION). A breach:
 *   stage 1 reminder with grace-days grace (made up = trust -2), stage 2 compensation (shortfall x today's market price
 *   or game animal value x markup, obligations and rights: value per year x markup), stage 3 termination at the
 *   breaches-to-terminate-th breach with a claim of the full amount plus open payments (INVESTOR_CLAIM like a tax
 *   bill: pay by button within claim-days, then a reminder per month, trust -5 and a payment delay, no interest).</li>
 *   <li>I5 The end is announced announce-months before (without a breach: extension offer with extension-probability);
 *   the buy-back / repayment is booked at the start of the last month when the money suffices, otherwise it becomes a
 *   claim. A refused payment (no money) stays open and can be paid by button (owner decision 2026-10-08).</li>
 * </ul>
 */
@Service
public class InvestorService {

    public static final String DELIVERY_RELATED = "INVESTOR_DELIVERY";
    public static final String PURCHASE_RELATED = "INVESTOR_PURCHASE";
    public static final String CASE_RELATED = "INVESTOR_CASE";

    private final InvestorContractRepository contracts;
    private final InvestorObligationRepository obligations;
    private final InvestorPeriodRepository periods;
    private final InvestorDeliveryRepository deliveries;
    private final InvestorPaymentRepository payments;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final OutboxInstructionRepository instructions;
    private final FactsService facts;
    private final FieldService fields;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final FarmReportService reports;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final InvestorOfferService offers;
    private final InvestorLedger ledger;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final PublicActionService publicActions;
    private final PaymentDelayService delays;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public InvestorService(InvestorContractRepository contracts, InvestorObligationRepository obligations,
                           InvestorPeriodRepository periods, InvestorDeliveryRepository deliveries,
                           InvestorPaymentRepository payments, ServiceCaseRepository cases, SavegameRepository savegames,
                           OutboxInstructionRepository instructions, FactsService facts, FieldService fields,
                           NeighborService neighbors, NeighborTradeService trade, FarmReportService reports,
                           LiquidityService liquidity, OutboxService outbox, InvestorOfferService offers,
                           InvestorLedger ledger, TrustScoreService trust, NarrationRequestService narration,
                           DiaryService diary, PublicActionService publicActions, PaymentDelayService delays,
                           FallbackTemplates labels, RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.obligations = obligations;
        this.periods = periods;
        this.deliveries = deliveries;
        this.payments = payments;
        this.cases = cases;
        this.savegames = savegames;
        this.instructions = instructions;
        this.facts = facts;
        this.fields = fields;
        this.neighbors = neighbors;
        this.trade = trade;
        this.reports = reports;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.offers = offers;
        this.ledger = ledger;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.publicActions = publicActions;
        this.delays = delays;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Investor cfg() {
        return props.getFormulas().getInvestor();
    }

    // ------------------------------------------------------------------------------------------ time

    /** FS25 year of a month of the contract (the term starts with period 1 of startYear). */
    static int yearOf(InvestorContract c, long monthIndex) {
        return c.getStartYear() + (int) Math.floorDiv(monthIndex - c.getStartMonthIndex(), 12L);
    }

    static boolean inTerm(InvestorContract c, long monthIndex) {
        return monthIndex >= c.getStartMonthIndex() && monthIndex <= c.getEndMonthIndex();
    }

    /** Month index of period 1 of an FS25 year of the contract. */
    static long yearStart(InvestorContract c, int year) {
        return c.getStartMonthIndex() + 12L * (year - c.getStartYear());
    }

    long currentMonth(Savegame sg) {
        return gameTime.monthIndex(sg, sg.getCurrentGameTime());
    }

    static InvestorConsideration type(InvestorObligation o) {
        return InvestorConsideration.of(o.getType());
    }

    /** Game time at which the period ends (start of the next month or year). */
    long periodEnd(Savegame sg, InvestorPeriod p) {
        InvestorContract c = p.getObligation().getContract();
        long nextMonth = p.isMonthly() ? p.getPeriodKey() + 1 : yearStart(c, p.getPeriodYear() + 1);
        return gameTime.monthStart(sg, nextMonth);
    }

    // ------------------------------------------------------------------------------------------ periods

    /** W1: litres due by the end of the year (cumulative; the last year the whole quantity). */
    static long w1Required(InvestorObligation o, int year) {
        InvestorContract c = o.getContract();
        int k = year - c.getStartYear() + 1;
        return k >= c.getYears() ? o.getQuantity() : k * o.getMinPerYear();
    }

    InvestorPeriod period(InvestorObligation o, long key, boolean monthly, int year) {
        return periods.findByObligationAndPeriodKey(o, key).orElseGet(() -> {
            InvestorPeriod p = new InvestorPeriod();
            p.setSavegame(o.getSavegame());
            p.setObligation(o);
            p.setPeriodKey(key);
            p.setMonthly(monthly);
            p.setPeriodYear(year);
            p.setStatus(InvestorPeriod.OPEN);
            p.setRequired(switch (type(o)) {
                case W1 -> w1Required(o, year);
                case W2, W3, A1, P2 -> o.getQuantity();
                case A3 -> Math.round(o.getHectares() * 100);
                case P4 -> 1L;
                default -> null;
            });
            return periods.save(p);
        });
    }

    /** The current period of a delivered consideration (null outside the term). */
    InvestorPeriod current(InvestorObligation o, long month) {
        InvestorContract c = o.getContract();
        if (!inTerm(c, month)) {
            return null;
        }
        int year = yearOf(c, month);
        return InvestorConsideration.MONTHLY.contains(type(o)) ? period(o, month, true, year) : period(o, year, false, year);
    }

    // ------------------------------------------------------------------------------------------ month change

    @EventListener
    @Order(89)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long m = e.monthIndex();
        int period = gameTime.anchor(sg).periodOf(m);
        for (InvestorContract c : ledger.active(sg)) {
            List<InvestorObligation> obs = obligations.findByContractOrderByIdAsc(c);
            if (inTerm(c, m - 1)) {
                monthlyCheck(sg, c, obs, m - 1);
            }
            if (period == 1 && inTerm(c, m - 1) && InvestorContract.ACTIVE.equals(c.getStatus())) {
                yearlyCheck(sg, c, obs, yearOf(c, m - 1));
            }
            if (!InvestorContract.ACTIVE.equals(c.getStatus())) {
                continue; // terminated by a check
            }
            if (m - 1 == c.getEndMonthIndex()) {
                finish(sg, c);
                continue;
            }
            if (m == c.getEndMonthIndex() - cfg().getAnnounceMonths() && !c.isAnnounced()) {
                announce(sg, c);
            }
            if (m == c.getEndMonthIndex() && !c.isRepaymentDue()) {
                repayment(sg, c);
            }
            if (inTerm(c, m)) {
                if (period == 1 || m == c.getStartMonthIndex()) {
                    schedule(c, obs, yearOf(c, m), m);
                }
                requests(sg, c, obs, m);
            }
        }
    }

    void monthlyCheck(Savegame sg, InvestorContract c, List<InvestorObligation> obs, long month) {
        for (InvestorObligation o : obs) {
            if (!InvestorContract.ACTIVE.equals(c.getStatus())) {
                return;
            }
            InvestorConsideration t = type(o);
            if (!InvestorConsideration.MONTHLY.contains(t)) {
                continue;
            }
            InvestorPeriod p = period(o, month, true, yearOf(c, month));
            if (!InvestorPeriod.OPEN.equals(p.getStatus())) {
                continue;
            }
            p.setCheckedGameTime(sg.getCurrentGameTime());
            if (t == InvestorConsideration.A2) {
                if (p.getHealthSamples() == 0) {
                    p.setStatus(InvestorPeriod.NO_DATA); // no stable data in the month: no breach (owner decision)
                } else if (p.getHealthSum() / p.getHealthSamples() >= o.getTarget()) {
                    fulfilled(c, p);
                } else {
                    breach(sg, c, p, null);
                }
            } else if (p.getDelivered() >= p.getRequired()) {
                fulfilled(c, p);
            } else {
                breach(sg, c, p, p.getRequired() - p.getDelivered());
            }
        }
    }

    void yearlyCheck(Savegame sg, InvestorContract c, List<InvestorObligation> obs, int year) {
        for (InvestorObligation o : obs) {
            if (!InvestorContract.ACTIVE.equals(c.getStatus())) {
                return;
            }
            switch (type(o)) {
                case W1 -> {
                    InvestorPeriod p = period(o, year, false, year);
                    p.setDelivered(o.getDeliveredTotal());
                    check(sg, c, p, o.getDeliveredTotal() >= p.getRequired(), p.getRequired() - o.getDeliveredTotal());
                }
                case A1 -> {
                    InvestorPeriod p = period(o, year, false, year);
                    check(sg, c, p, p.getDelivered() >= p.getRequired(), p.getRequired() - p.getDelivered());
                }
                case A3 -> {
                    InvestorPeriod p = period(o, year, false, year);
                    long area = Math.round(cropHectares(sg, o.getFillType(), year) * 100);
                    p.setDelivered(area);
                    check(sg, c, p, area >= p.getRequired(), p.getRequired() - area);
                }
                case A4 -> {
                    if (year == c.getStartYear()) {
                        InvestorPeriod p = period(o, year, false, year);
                        boolean reached = growthReached(sg, o);
                        o.setFulfilled(reached);
                        check(sg, c, p, reached, null);
                    }
                }
                case R2 -> payout(sg, c, Math.round(c.getAmount() * o.getRate()), year,
                        "Ausschüttung " + year + " an " + offers.kindLabel(c.getKind()));
                case R1 -> reports.list(sg).stream().filter(r -> r.year() == year).findFirst()
                        .map(r -> r.totals().operatingResult()).filter(r -> r > 0)
                        .ifPresent(r -> payout(sg, c, Math.round(r * o.getRate()), year, "Gewinnanteil " + year
                                + " an " + offers.kindLabel(c.getKind())));
                default -> {
                    // monthly ones above; P1 on a sale, P2 / P4 by their case, P3 / P5 without a check
                }
            }
        }
    }

    private void check(Savegame sg, InvestorContract c, InvestorPeriod p, boolean ok, Long shortfall) {
        if (!InvestorPeriod.OPEN.equals(p.getStatus())) {
            return;
        }
        p.setCheckedGameTime(sg.getCurrentGameTime());
        if (ok) {
            fulfilled(c, p);
        } else {
            breach(sg, c, p, shortfall);
        }
    }

    private void fulfilled(InvestorContract c, InvestorPeriod p) {
        p.setStatus(InvestorPeriod.FULFILLED);
        trust.recordEvent(c.getCharacter(), cfg().getFulfilledTrustDelta(), TrustReason.INVESTOR_FULFILLED,
                offers.describe(p.getObligation()));
    }

    /** A3: hectares of the own fields on which the crop stood in the FS25 year (field_crop_history, area of the field). */
    double cropHectares(Savegame sg, String crop, int year) {
        Map<Integer, Double> area = new HashMap<>();
        for (FieldRecord r : fields.records(sg)) {
            area.put(r.getFarmlandId(), r.getHectares());
        }
        Set<Integer> farmlands = new LinkedHashSet<>();
        for (FieldCropHistory h : fields.cropsOfYear(sg, year)) {
            if (crop != null && crop.equalsIgnoreCase(h.getFruitType())) {
                farmlands.add(h.getFarmlandId());
            }
        }
        return farmlands.stream().mapToDouble(id -> Objects.requireNonNullElse(area.get(id), 0.0)).sum();
    }

    /** A4: own area (or animals) of the latest export reaches the target. */
    boolean growthReached(Savegame sg, InvestorObligation o) {
        FarmFacts f = facts.latest(sg).orElse(null);
        InvestorOfferService.Farm farm = offers.farm(sg, f);
        double value = "AREA".equals(o.getTargetKind()) ? farm.ownHectares() : farm.animals();
        return value + 1e-9 >= o.getTarget();
    }

    // ------------------------------------------------------------------------------------------ P2 / P4 requests

    /** At the start of a term year: the month of the P2 request and of the P4 visits (random within the year). */
    void schedule(InvestorContract c, List<InvestorObligation> obs, int year, long firstMonth) {
        long yearStartIdx = yearStart(c, year);
        for (InvestorObligation o : obs) {
            InvestorConsideration t = type(o);
            int n = t == InvestorConsideration.P2 ? 1 : t == InvestorConsideration.P4 ? cfg().getVisitsPerYear() : 0;
            for (int i = 0; i < n; i++) {
                long key = t == InvestorConsideration.P4 ? year * 10L + i : year;
                InvestorPeriod p = period(o, key, false, year);
                if (p.getScheduledMonthIndex() == null) {
                    long from = Math.max(firstMonth, yearStartIdx);
                    p.setScheduledMonthIndex(from + random.intBetween(0, (int) (yearStartIdx + 11 - from)));
                }
            }
        }
    }

    /** The scheduled P2 request or P4 invitation of this month. */
    void requests(Savegame sg, InvestorContract c, List<InvestorObligation> obs, long month) {
        for (InvestorObligation o : obs) {
            InvestorConsideration t = type(o);
            if (t != InvestorConsideration.P2 && t != InvestorConsideration.P4) {
                continue;
            }
            for (InvestorPeriod p : periods.findByObligationOrderByPeriodKeyAsc(o)) {
                if (p.getCaseId() == null && Objects.equals(p.getScheduledMonthIndex(), month)
                        && InvestorPeriod.OPEN.equals(p.getStatus())) {
                    if (t == InvestorConsideration.P2) {
                        purchaseRequest(sg, c, o, p);
                    } else {
                        visitInvitation(sg, c, p);
                    }
                }
            }
        }
    }

    void purchaseRequest(Savegame sg, InvestorContract c, InvestorObligation o, InvestorPeriod p) {
        double best = InvestorOfferService.bestPrice(facts.latest(sg).orElse(null), o.getFillType());
        if (best <= 0) {
            p.setStatus(InvestorPeriod.NO_DATA); // no market price this month: no request, no breach
            return;
        }
        long unit = Math.round(best * (1 - cfg().getPurchaseDiscount()));
        ServiceCase sc = newCase(sg, c, CaseKind.INVESTOR_PURCHASE, "Vorkaufsrecht " + offers.kindLabel(c.getKind()),
                cfg().getPurchaseAnswerDays());
        sc.setReference(o.getFillType());
        sc.setQuantity(o.getQuantity().intValue());
        sc.setCostAmount(unit);
        sc.setOfferAmount(Math.round(o.getQuantity() / 1000.0 * unit));
        sc.setDirection("SELL");
        p.setCaseId(sc.getId());
        narration.request(sg, NarrationEventType.INVESTOR_PURCHASE_REQUEST).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("fillType", o.getFillType()).put("quantity", o.getQuantity())
                        .put("unitPrice", unit).put("total", sc.getOfferAmount())
                        .put("answerDays", Math.round(cfg().getPurchaseAnswerDays())).build())
                .category(CommunicationCategory.CREDIT).related(CASE_RELATED, sc.getId())
                .formLink("/bank?case=" + sc.getId()).submit();
    }

    void visitInvitation(Savegame sg, InvestorContract c, InvestorPeriod p) {
        ServiceCase sc = newCase(sg, c, CaseKind.INVESTOR_VISIT, "Besuch " + offers.kindLabel(c.getKind()),
                cfg().getVisitAnswerDays());
        p.setCaseId(sc.getId());
        narration.request(sg, NarrationEventType.INVESTOR_VISIT_INVITATION).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("answerDays", Math.round(cfg().getVisitAnswerDays())).build())
                .category(CommunicationCategory.CREDIT).related(CASE_RELATED, sc.getId())
                .formLink("/bank?case=" + sc.getId()).submit();
    }

    private ServiceCase newCase(Savegame sg, InvestorContract c, CaseKind kind, String title, double days) {
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(c.getCharacter());
        sc.setTitle(title);
        sc.setExternalId(String.valueOf(c.getId()));
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(days));
        sc.setCreatedAt(Instant.now());
        return cases.save(sc);
    }

    private ServiceCase openCase(Savegame sg, Long id, CaseKind kind) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != kind || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    /** P2 "Liefern": the whole amount from the own silos at the fixed price (STORAGE_TRANSFER OUT + GOODS_SALE, like G2). */
    @Transactional
    public ServiceCase deliverPurchase(Savegame sg, Long caseId) {
        ServiceCase sc = openCase(sg, caseId, CaseKind.INVESTOR_PURCHASE);
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            throw new BusinessRuleException("TRADE_NO_SILOS", "Der Mod meldet deine Silos nicht – bitte den Mod "
                    + "FS25_RPSim aktualisieren.");
        }
        long liters = sc.getQuantity();
        if (trade.playerStock(sg, neighbors.tradeStorage(f), sc.getReference()) < liters) {
            throw new BusinessRuleException("TRADE_NO_STOCK", "In deinen Silos liegt nicht genug "
                    + labels.label(sc.getReference()) + " (" + InvestorOfferService.liters(liters) + " l).");
        }
        outbox.storageDeal(sg, false, sc.getReference(), liters, sc.getOfferAmount(), MoneyReason.GOODS_SALE,
                "Vorkaufsrecht: " + InvestorOfferService.liters(liters) + " l " + labels.label(sc.getReference())
                        + " an " + sc.getCharacter().getName(), new Related(PURCHASE_RELATED, sc.getId()));
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    /** P2 / P4 "Ablehnen": a breach (stages of I4). */
    @Transactional
    public ServiceCase declineRequest(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        openCase(sg, caseId, sc.getKind());
        refused(sg, sc, CaseStatus.DECLINED, "DECLINED");
        return sc;
    }

    /** P4 "Zusagen". */
    @Transactional
    public ServiceCase acceptVisit(Savegame sg, Long caseId) {
        ServiceCase sc = openCase(sg, caseId, CaseKind.INVESTOR_VISIT);
        closeCase(sg, sc, CaseStatus.SETTLED, "ACCEPTED");
        periods.findByCaseId(sc.getId()).ifPresent(p -> {
            InvestorContract c = p.getObligation().getContract();
            fulfilled(c, p);
            diary.addAuto(sg, "CREDIT", "Besuch des Investors", sc.getCharacter().getName() + " ("
                    + offers.kindLabel(c.getKind()) + ") kommt zu Besuch auf den Hof.", OfferRelated.CONTRACT, c.getId());
        });
        return sc;
    }

    private void refused(Savegame sg, ServiceCase sc, CaseStatus status, String resolution) {
        closeCase(sg, sc, status, resolution);
        periods.findByCaseId(sc.getId()).ifPresent(p -> {
            InvestorContract c = p.getObligation().getContract();
            if (InvestorContract.ACTIVE.equals(c.getStatus()) && InvestorPeriod.OPEN.equals(p.getStatus())) {
                p.setCheckedGameTime(sg.getCurrentGameTime());
                breach(sg, c, p, sc.getKind() == CaseKind.INVESTOR_PURCHASE ? (long) sc.getQuantity() : 1L);
            }
        });
    }

    private static void closeCase(Savegame sg, ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ deliveries

    /** Open quantity of a delivered consideration: shortfalls in a grace plus the rest of the current period. */
    public long outstanding(InvestorObligation o, long month) {
        long open = 0;
        for (InvestorPeriod p : periods.findByObligationOrderByPeriodKeyAsc(o)) {
            if (InvestorPeriod.REMINDED.equals(p.getStatus()) && p.getRequired() != null) {
                open += type(o) == InvestorConsideration.W1 ? Math.max(0, p.getRequired() - o.getDeliveredTotal())
                        : Math.max(0, p.getRequired() - p.getDelivered());
            }
        }
        InvestorPeriod cur = current(o, month);
        if (type(o) == InvestorConsideration.W1) {
            open = inTerm(o.getContract(), month) || open > 0 ? Math.max(0, o.getQuantity() - o.getDeliveredTotal()) : 0;
        } else if (cur != null && InvestorPeriod.OPEN.equals(cur.getStatus())) {
            open += Math.max(0, cur.getRequired() - cur.getDelivered());
        }
        long pending = deliveries.findByObligationOrderByIdAsc(o).stream()
                .filter(d -> InvestorDelivery.SENT.equals(d.getStatus())).mapToLong(InvestorDelivery::getQuantity).sum();
        return Math.max(0, open - pending);
    }

    /** Still due in the current period (reminder, tasks): W1 up to the cumulative minimum of the year. */
    long dueThisPeriod(InvestorObligation o, long month) {
        InvestorPeriod cur = current(o, month);
        if (cur == null || !InvestorPeriod.OPEN.equals(cur.getStatus())) {
            return 0;
        }
        long pending = deliveries.findByObligationOrderByIdAsc(o).stream()
                .filter(d -> InvestorDelivery.SENT.equals(d.getStatus())).mapToLong(InvestorDelivery::getQuantity).sum();
        long done = type(o) == InvestorConsideration.W1 ? o.getDeliveredTotal() : cur.getDelivered();
        return Math.max(0, cur.getRequired() - done - pending);
    }

    /** "Liefern" of goods (W1 / W2), milk (W3, from a stable) or animals (A1, from a stable). */
    @Transactional
    public InvestorDelivery deliver(Savegame sg, Long obligationId, long quantity, String husbandryUniqueId) {
        InvestorObligation o = obligations.findById(obligationId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("obligation " + obligationId));
        InvestorConsideration t = type(o);
        InvestorContract c = o.getContract();
        if (!InvestorConsideration.DELIVERED.contains(t)) {
            throw new BusinessRuleException("INVESTOR_NOT_DELIVERED", "Diese Gegenleistung wird nicht geliefert.");
        }
        long month = currentMonth(sg);
        boolean grace = periods.findByObligationOrderByPeriodKeyAsc(o).stream()
                .anyMatch(p -> InvestorPeriod.REMINDED.equals(p.getStatus()));
        if (!grace && (!InvestorContract.ACTIVE.equals(c.getStatus()) || month < c.getStartMonthIndex())) {
            throw new BusinessRuleException("INVESTOR_NOT_RUNNING", InvestorContract.ACTIVE.equals(c.getStatus())
                    ? "Die Laufzeit beginnt mit dem Jahr " + c.getStartYear() + "." : "Dieser Vertrag läuft nicht mehr.");
        }
        long open = outstanding(o, month);
        if (quantity <= 0 || quantity > open) {
            throw new BusinessRuleException("INVESTOR_QUANTITY", "Es sind noch " + open + " "
                    + (t == InvestorConsideration.A1 ? "Tiere" : "Liter") + " offen.");
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        switch (t) {
            case W1, W2 -> {
                if (f == null || f.tradeStorage() == null) {
                    throw new BusinessRuleException("TRADE_NO_SILOS", "Der Mod meldet deine Silos nicht – bitte den "
                            + "Mod FS25_RPSim aktualisieren.");
                }
                if (trade.playerStock(sg, neighbors.tradeStorage(f), o.getFillType()) < quantity) {
                    throw new BusinessRuleException("TRADE_NO_STOCK", "In deinen Silos liegt nicht genug "
                            + labels.label(o.getFillType()) + ".");
                }
            }
            case W3 -> {
                BridgeDtos.Husbandry h = husbandry(f, husbandryUniqueId);
                double stock = h.storage() == null ? 0 : h.storage().stream()
                        .filter(s -> s != null && o.getFillType().equals(s.fillType()) && s.amount() != null)
                        .mapToDouble(BridgeDtos.StorageEntry::amount).sum();
                if (stock < quantity) {
                    throw new BusinessRuleException("HUSBANDRY_NO_STOCK", "In diesem Stall liegen nur "
                            + Math.round(stock) + " l " + labels.label(o.getFillType()) + ".");
                }
            }
            case A1 -> {
                BridgeDtos.Husbandry h = husbandry(f, husbandryUniqueId);
                int count = h.subTypes() == null ? 0 : h.subTypes().stream()
                        .filter(s -> s != null && o.getSubType().equals(s.name()) && s.count() != null)
                        .mapToInt(BridgeDtos.SubTypeCount::count).sum();
                if (count < quantity) {
                    throw new BusinessRuleException("NOT_ENOUGH_ANIMALS", "In diesem Stall stehen nur " + count + " "
                            + labels.label(o.getSubType()) + ".");
                }
            }
            default -> throw new IllegalStateException();
        }
        InvestorDelivery d = new InvestorDelivery();
        d.setSavegame(sg);
        d.setObligation(o);
        d.setQuantity(quantity);
        d.setStatus(InvestorDelivery.SENT);
        d.setSentGameTime(sg.getCurrentGameTime());
        d.setHusbandryUniqueId(t == InvestorConsideration.W1 || t == InvestorConsideration.W2 ? null : husbandryUniqueId);
        deliveries.save(d);
        Related related = new Related(DELIVERY_RELATED, d.getId());
        d.setInstructionId((switch (t) {
            case W1, W2 -> outbox.storageTransfer(sg, false, o.getFillType(), quantity, related);
            case W3 -> outbox.husbandryTransfer(sg, husbandryUniqueId, o.getFillType(), quantity, related);
            default -> outbox.animalTransfer(sg, husbandryUniqueId, o.getSubType(), (int) quantity, related);
        }).getInstructionId());
        return d;
    }

    private static BridgeDtos.Husbandry husbandry(FarmFacts f, String id) {
        if (f == null || f.husbandries() == null || id == null) {
            throw new BusinessRuleException("HUSBANDRY_NOT_FOUND", "Bitte einen Stall wählen.");
        }
        return f.husbandries().stream().filter(h -> h != null && id.equals(h.husbandryUniqueId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("HUSBANDRY_NOT_FOUND", "Diesen Stall gibt es nicht."));
    }

    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || e.relatedId() == null) {
            return;
        }
        if (DELIVERY_RELATED.equals(e.relatedType())) {
            deliveries.findById(e.relatedId()).filter(d -> InvestorDelivery.SENT.equals(d.getStatus()))
                    .ifPresent(this::delivered); // a re-sent delivery after a rewind does not count twice
        } else if (PURCHASE_RELATED.equals(e.relatedType())) {
            boolean transfer = instructions.findByInstructionId(e.instructionId())
                    .map(o -> o.getType() == InstructionType.STORAGE_TRANSFER).orElse(false);
            ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
            if (!transfer || sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS) {
                return;
            }
            Savegame sg = sc.getSavegame();
            closeCase(sg, sc, CaseStatus.SETTLED, "DELIVERED");
            periods.findByCaseId(sc.getId()).ifPresent(p -> {
                p.setDelivered(sc.getQuantity());
                InvestorContract c = p.getObligation().getContract();
                if (InvestorPeriod.OPEN.equals(p.getStatus())) {
                    fulfilled(c, p);
                }
                diary.addAuto(sg, "TRADE", "Vorkaufsrecht erfüllt", InvestorOfferService.liters((long) sc.getQuantity())
                        + " l " + labels.label(sc.getReference()) + " an " + sc.getCharacter().getName() + " verkauft ("
                        + InvestorOfferService.euro(sc.getOfferAmount()) + ").", OfferRelated.CONTRACT, c.getId());
            });
        }
    }

    /** The mod took the goods, milk or animals: the delivery counts (shortfalls in a grace first). */
    void delivered(InvestorDelivery d) {
        Savegame sg = d.getSavegame();
        InvestorObligation o = d.getObligation();
        d.setStatus(InvestorDelivery.DONE);
        d.setDoneGameTime(sg.getCurrentGameTime());
        d.setDeliveryYear(offers.currentYear(sg, facts.latest(sg).orElse(null)));
        long q = d.getQuantity();
        long month = currentMonth(sg);
        if (type(o) == InvestorConsideration.W1) {
            o.setDeliveredTotal(o.getDeliveredTotal() + q);
            for (InvestorPeriod p : periods.findByObligationOrderByPeriodKeyAsc(o)) {
                if (InvestorPeriod.REMINDED.equals(p.getStatus()) && o.getDeliveredTotal() >= p.getRequired()) {
                    madeUp(sg, p);
                }
            }
            InvestorPeriod cur = current(o, month);
            if (cur != null) {
                cur.setDelivered(o.getDeliveredTotal());
            }
        } else {
            for (InvestorPeriod p : periods.findByObligationOrderByPeriodKeyAsc(o)) {
                if (q > 0 && InvestorPeriod.REMINDED.equals(p.getStatus())) {
                    long take = Math.min(q, p.getRequired() - p.getDelivered());
                    p.setDelivered(p.getDelivered() + take);
                    q -= take;
                    if (p.getDelivered() >= p.getRequired()) {
                        madeUp(sg, p);
                    }
                }
            }
            InvestorPeriod cur = current(o, month);
            if (q > 0 && cur != null) {
                cur.setDelivered(cur.getDelivered() + q);
            }
        }
        maybeRetire(o.getContract());
    }

    /** FailedInstructionService: the mod refused a delivery - it does not count. */
    @Transactional
    public boolean onDeliveryFailed(Long deliveryId) {
        InvestorDelivery d = deliveries.findById(deliveryId).orElse(null);
        if (d == null || InvestorDelivery.FAILED.equals(d.getStatus())) {
            return false;
        }
        if (InvestorDelivery.DONE.equals(d.getStatus()) && type(d.getObligation()) == InvestorConsideration.W1) {
            InvestorObligation o = d.getObligation(); // a re-sent delivery after a rewind was refused
            o.setDeliveredTotal(Math.max(0, o.getDeliveredTotal() - d.getQuantity()));
        }
        d.setStatus(InvestorDelivery.FAILED);
        return true;
    }

    /** FailedInstructionService: the P2 delivery was refused - nothing booked, the request stays open. */
    @Transactional
    public boolean onPurchaseFailed(Long caseId) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.INVESTOR_PURCHASE || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return false;
        }
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        return true;
    }

    // ------------------------------------------------------------------------------------------ A2 / P1 events

    /** A2: the mean health of the stables with animals of every export joins the month of the contract. */
    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElse(null);
        if (sg == null) {
            return;
        }
        List<InvestorContract> active = ledger.active(sg);
        if (active.isEmpty()) {
            return;
        }
        Double health = meanHealth(facts.latest(sg).orElse(null));
        if (health == null) {
            return;
        }
        long month = currentMonth(sg);
        for (InvestorContract c : active) {
            if (!inTerm(c, month)) {
                continue;
            }
            for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                if (type(o) == InvestorConsideration.A2) {
                    InvestorPeriod p = period(o, month, true, yearOf(c, month));
                    p.setHealthSum(p.getHealthSum() + health);
                    p.setHealthSamples(p.getHealthSamples() + 1);
                }
            }
        }
    }

    /** Mean health of the stables with animals (null without one). */
    static Double meanHealth(FarmFacts f) {
        if (f == null || f.husbandries() == null) {
            return null;
        }
        return f.husbandries().stream().filter(h -> h != null && h.health() != null && h.subTypes() != null
                        && h.subTypes().stream().anyMatch(s -> s != null && s.count() != null && s.count() > 0))
                .mapToDouble(BridgeDtos.Husbandry::health).average().stream().boxed().findFirst().orElse(null);
    }

    /** P1: a field sold in the game menu (R2-D2 detection) without the investor's consent is a breach. */
    @EventListener
    @Transactional
    public void onFarmlandBypass(FarmlandBypassEvent e) {
        if (e.purchase()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (InvestorObligation o : ledger.vetoes(sg, e.farmlandId())) {
            InvestorContract c = o.getContract();
            InvestorPeriod p = period(o, now, false, yearOf(c, Math.max(c.getStartMonthIndex(), currentMonth(sg))));
            p.setRequired(null);
            p.setCheckedGameTime(now);
            breach(sg, c, p, null);
        }
    }

    /** P1 "Zustimmung einholen": the investor always agrees by mail (owner decision, like the bank R3-K1). */
    @Transactional
    public InvestorObligation fieldConsent(Savegame sg, Long contractId, int farmlandId) {
        InvestorContract c = contract(sg, contractId);
        InvestorObligation o = obligations.findByContractOrderByIdAsc(c).stream()
                .filter(x -> type(x) == InvestorConsideration.P1).findFirst()
                .orElseThrow(() -> new BusinessRuleException("NO_VETO", "Dieser Investor hat kein Vetorecht."));
        if (!InvestorContract.ACTIVE.equals(c.getStatus())) {
            throw new BusinessRuleException("INVESTOR_NOT_RUNNING", "Dieser Vertrag läuft nicht mehr.");
        }
        if (InvestorLedger.consented(o, farmlandId)) {
            return o;
        }
        o.setConsents(o.getConsents() == null || o.getConsents().isBlank() ? String.valueOf(farmlandId)
                : o.getConsents() + "," + farmlandId);
        narration.request(sg, NarrationEventType.INVESTOR_FIELD_CONSENT).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).build())
                .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Zustimmung des Investors", c.getCharacter().getName() + " ist mit dem Verkauf von Feld "
                + farmlandId + " einverstanden.", OfferRelated.CONTRACT, c.getId());
        return o;
    }

    public InvestorContract contract(Savegame sg, Long id) {
        return contracts.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("investor contract " + id));
    }

    // ------------------------------------------------------------------------------------------ I4 breach

    /** A consideration of a period is not fulfilled: stage 1 (reminder) or, at the n-th breach, stage 3. */
    void breach(Savegame sg, InvestorContract c, InvestorPeriod p, Long shortfall) {
        p.setBreach(true);
        p.setShortfall(shortfall);
        c.setBreaches(c.getBreaches() + 1);
        boolean named = named(c);
        if (named) {
            publicActions.record(sg, PublicActionType.INVESTOR_BREACH, cfg().getPublicBreachDelta(),
                    "Vertragsbruch gegenüber " + offers.kindLabel(c.getKind()));
        }
        String what = offers.describe(p.getObligation());
        if (c.getBreaches() >= cfg().getBreachesToTerminate()) {
            p.setStatus(InvestorPeriod.CANCELLED);
            terminate(sg, c);
            return;
        }
        long now = sg.getCurrentGameTime();
        p.setStatus(InvestorPeriod.REMINDED);
        p.setGraceUntil(now + GameTime.days(cfg().getGraceDays()));
        ServiceCase sc = newCase(sg, c, CaseKind.INVESTOR_REMINDER, "Mahnung " + offers.kindLabel(c.getKind()),
                cfg().getGraceDays());
        sc.setReference(p.getObligation().getType());
        sc.setQuantity(shortfall == null ? null : shortfall.intValue());
        p.setReminderCaseId(sc.getId());
        narration.request(sg, NarrationEventType.INVESTOR_REMINDER).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("what", what).put("shortfall", shortfallText(p.getObligation(), shortfall))
                        .put("graceDays", Math.round(cfg().getGraceDays())).put("breaches", c.getBreaches()).build())
                .category(CommunicationCategory.CREDIT).related(CASE_RELATED, sc.getId())
                .formLink("/bank?case=" + sc.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Mahnung des Investors", c.getCharacter().getName() + " mahnt: " + what + " ("
                + c.getBreaches() + ". Vertragsbruch).", OfferRelated.CONTRACT, c.getId());
    }

    String shortfallText(InvestorObligation o, Long shortfall) {
        if (shortfall == null) {
            return "nicht erfüllt";
        }
        return switch (type(o)) {
            case W1, W2, W3, P2 -> InvestorOfferService.liters(shortfall) + " l";
            case A1 -> shortfall + " Tiere";
            case A3 -> String.format(Locale.GERMANY, "%.2f ha", shortfall / 100.0);
            default -> "nicht erfüllt";
        };
    }

    private boolean named(InvestorContract c) {
        return obligations.findByContractOrderByIdAsc(c).stream().anyMatch(o -> type(o) == InvestorConsideration.P5);
    }

    void madeUp(Savegame sg, InvestorPeriod p) {
        p.setStatus(InvestorPeriod.MADE_UP);
        InvestorContract c = p.getObligation().getContract();
        trust.recordEvent(c.getCharacter(), cfg().getReminderTrustDelta(), TrustReason.INVESTOR_REMINDER,
                offers.describe(p.getObligation()));
        if (p.getReminderCaseId() != null) {
            cases.findById(p.getReminderCaseId()).filter(sc -> sc.getStatus() == CaseStatus.AWAITING_PLAYER)
                    .ifPresent(sc -> closeCase(sg, sc, CaseStatus.SETTLED, "MADE_UP"));
        }
    }

    /** Stage 2 after the grace: the compensation settles the shortfall. */
    void afterGrace(Savegame sg, InvestorPeriod p) {
        InvestorObligation o = p.getObligation();
        InvestorContract c = o.getContract();
        if (type(o) == InvestorConsideration.A4 && growthReached(sg, o)) {
            o.setFulfilled(true);
            madeUp(sg, p);
            return;
        }
        long amount = compensation(sg, o, p);
        p.setCompensation(amount);
        p.setStatus(InvestorPeriod.COMPENSATED);
        if (p.getReminderCaseId() != null) {
            cases.findById(p.getReminderCaseId()).filter(sc -> sc.getStatus() == CaseStatus.AWAITING_PLAYER)
                    .ifPresent(sc -> closeCase(sg, sc, CaseStatus.EXPIRED, "COMPENSATED"));
        }
        String what = offers.describe(o);
        trust.recordEvent(c.getCharacter(), cfg().getCompensationTrustDelta(), TrustReason.INVESTOR_COMPENSATION, what);
        if (amount > 0) {
            book(sg, c, InvestorPayment.COMPENSATION, amount, MoneyReason.INVESTOR_COMPENSATION,
                    "Ausgleichszahlung an " + offers.kindLabel(c.getKind()));
        }
        narration.request(sg, NarrationEventType.INVESTOR_COMPENSATION).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("what", what).put("compensation", amount)
                        .put("breaches", c.getBreaches()).put("breachesToTerminate", cfg().getBreachesToTerminate()).build())
                .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Ausgleichszahlung an den Investor", what + ": " + InvestorOfferService.euro(amount)
                + " Ausgleich.", OfferRelated.CONTRACT, c.getId());
        maybeRetire(c);
    }

    /**
     * Stage 2 amount: goods and milk shortfall x today's best market price, animals x game value per animal, P2 the
     * refused litres x market price; obligations and rights their value per year (P4 per date) - all x markup.
     */
    long compensation(Savegame sg, InvestorObligation o, InvestorPeriod p) {
        double markup = cfg().getCompensationMarkup();
        FarmFacts f = facts.latest(sg).orElse(null);
        long shortfall = p.getShortfall() == null ? 0 : p.getShortfall();
        if (type(o) == InvestorConsideration.W1) {
            shortfall = Math.max(0, p.getRequired() - o.getDeliveredTotal());
        } else if (InvestorConsideration.DELIVERED.contains(type(o))) {
            shortfall = Math.max(0, p.getRequired() - p.getDelivered());
        }
        return switch (type(o)) {
            case W1, W2, W3, P2 -> {
                double price = InvestorOfferService.bestPrice(f, o.getFillType());
                yield Math.round(shortfall / 1000.0 * (price > 0 ? price : nz(o.getUnitPrice())) * markup);
            }
            case A1 -> {
                double value = offers.farm(sg, f).animalValue().getOrDefault(o.getSubType(), 0.0);
                yield Math.round(shortfall * (value > 0 ? value : nz(o.getUnitPrice())) * markup);
            }
            case P4 -> Math.round(cfg().getValues().getOrDefault("P4", 0L) * markup);
            default -> Math.round(o.getValuePerYear() * markup);
        };
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /** Stage 3: the investor terminates; the claim = the amount still in the farm plus the open payments. */
    void terminate(Savegame sg, InvestorContract c) {
        long now = sg.getCurrentGameTime();
        boolean capital = ledger.capitalIn(sg, c);
        c.setStatus(InvestorContract.TERMINATED);
        c.setEndReason("BREACH");
        c.setEndedGameTime(now);
        for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
            for (InvestorPeriod p : periods.findByObligationOrderByPeriodKeyAsc(o)) {
                if (InvestorPeriod.REMINDED.equals(p.getStatus())) {
                    p.setStatus(InvestorPeriod.CANCELLED);
                }
            }
        }
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.INVESTOR_REMINDER,
                CaseKind.INVESTOR_PURCHASE, CaseKind.INVESTOR_VISIT, CaseKind.INVESTOR_OFFER))) {
            boolean own = String.valueOf(c.getId()).equals(sc.getExternalId());
            if (own && (sc.getStatus() == CaseStatus.AWAITING_PLAYER || sc.getStatus() == CaseStatus.IN_PROGRESS)) {
                closeCase(sg, sc, CaseStatus.EXPIRED, "CONTRACT_TERMINATED");
                if (sc.getKind() == CaseKind.INVESTOR_OFFER) {
                    offers.packagesOf(sc).forEach(x -> x.setStatus(InvestorContract.EXPIRED));
                }
            }
        }
        if (c.getExtendedBy() != null) {
            contracts.findById(c.getExtendedBy()).ifPresent(next -> {
                next.setStatus(InvestorContract.TERMINATED);
                next.setEndReason("PREDECESSOR_TERMINATED");
                next.setEndedGameTime(now);
            });
        }
        trust.recordEvent(c.getCharacter(), cfg().getTerminationTrustDelta(), TrustReason.INVESTOR_TERMINATION,
                offers.kindLabel(c.getKind()));
        if (named(c)) {
            publicActions.record(sg, PublicActionType.INVESTOR_BREACH, cfg().getPublicBreachDelta(),
                    "Kündigung durch " + offers.kindLabel(c.getKind()));
        }
        if (capital) {
            openPayment(sg, c, InvestorPayment.REPAYMENT, c.getAmount(), c.silent() ? "Rückkauf der Beteiligung"
                    : "Rückzahlung des Nachrangdarlehens");
        }
        ServiceCase claim = claim(sg, c).orElse(null);
        long total = claim == null ? 0 : claim.getOfferAmount();
        narration.request(sg, NarrationEventType.INVESTOR_TERMINATION).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("breaches", c.getBreaches()).put("claim", total)
                        .put("claimDays", Math.round(cfg().getClaimDays())).build())
                .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId())
                .formLink(claim == null ? "/bank/investoren" : "/bank?case=" + claim.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Investor kündigt", c.getCharacter().getName() + " (" + offers.kindLabel(c.getKind())
                + ") kündigt nach " + c.getBreaches() + " Vertragsbrüchen und fordert "
                + InvestorOfferService.euro(total) + " zurück.", OfferRelated.CONTRACT, c.getId());
        maybeRetire(c);
    }

    // ------------------------------------------------------------------------------------------ payments

    private InvestorPayment payment(Savegame sg, InvestorContract c, String kind, long amount, String status, String note) {
        InvestorPayment p = new InvestorPayment();
        p.setSavegame(sg);
        p.setContract(c);
        p.setKind(kind);
        p.setAmount(amount);
        p.setPaymentYear(offers.currentYear(sg, facts.latest(sg).orElse(null)));
        p.setGameTime(sg.getCurrentGameTime());
        p.setStatus(status);
        p.setNote(note);
        return payments.save(p);
    }

    private InvestorPayment openPayment(Savegame sg, InvestorContract c, String kind, long amount, String note) {
        return payment(sg, c, kind, amount, InvestorPayment.OPEN, note);
    }

    /** Books a payment to the investor (a refused booking stays open). */
    InvestorPayment book(Savegame sg, InvestorContract c, String kind, long amount, MoneyReason reason, String note) {
        InvestorPayment p = payment(sg, c, kind, amount, InvestorPayment.BOOKED, note);
        p.setInstructionId(outbox.money(sg, -amount, reason, note, new Related(OfferRelated.PAYMENT, p.getId()))
                .getInstructionId());
        return p;
    }

    private void payout(Savegame sg, InvestorContract c, long amount, int year, String note) {
        if (amount <= 0) {
            return;
        }
        InvestorPayment p = book(sg, c, InvestorPayment.PAYOUT, amount, MoneyReason.INVESTOR_PAYOUT, note);
        p.setPaymentYear(year);
        diary.addAuto(sg, "CREDIT", "Zahlung an den Investor", note + ": " + InvestorOfferService.euro(amount) + ".",
                OfferRelated.CONTRACT, c.getId());
    }

    static MoneyReason reason(InvestorPayment p) {
        return switch (p.getKind()) {
            case InvestorPayment.PAYOUT -> MoneyReason.INVESTOR_PAYOUT;
            case InvestorPayment.COMPENSATION -> MoneyReason.INVESTOR_COMPENSATION;
            default -> MoneyReason.INVESTOR_REPAYMENT;
        };
    }

    /** FailedInstructionService: the mod refused a payment (no money) - it stays open (owner decision 2026-10-08). */
    @Transactional
    public boolean onPaymentFailed(Long paymentId) {
        InvestorPayment p = payments.findById(paymentId).orElse(null);
        if (p == null || !InvestorPayment.BOOKED.equals(p.getStatus()) || InvestorPayment.CAPITAL.equals(p.getKind())) {
            return false;
        }
        p.setStatus(InvestorPayment.OPEN);
        p.setInstructionId(null);
        return true;
    }

    /** "Bezahlen" of an open payment of a running contract. */
    @Transactional
    public InvestorPayment payOpen(Savegame sg, Long paymentId) {
        InvestorPayment p = payments.findById(paymentId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("payment " + paymentId));
        if (!InvestorPayment.OPEN.equals(p.getStatus())) {
            throw new BusinessRuleException("PAYMENT_CLOSED", "Diese Zahlung ist nicht offen.");
        }
        if (liquidity.available(sg) < p.getAmount()) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Zahlung nicht.");
        }
        p.setStatus(InvestorPayment.BOOKED);
        p.setInstructionId(outbox.money(sg, -p.getAmount(), reason(p), p.getNote(),
                new Related(OfferRelated.PAYMENT, p.getId())).getInstructionId());
        maybeRetire(p.getContract());
        return p;
    }

    /** A claim case (INVESTOR_CLAIM) over every open payment of the contract; empty without one. */
    Optional<ServiceCase> claim(Savegame sg, InvestorContract c) {
        List<InvestorPayment> open = payments.findByContractOrderByIdAsc(c).stream()
                .filter(p -> InvestorPayment.OPEN.equals(p.getStatus())).toList();
        long total = open.stream().mapToLong(InvestorPayment::getAmount).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        ServiceCase sc = newCase(sg, c, CaseKind.INVESTOR_CLAIM, "Rückforderung " + offers.kindLabel(c.getKind()),
                cfg().getClaimDays());
        sc.setOfferAmount(total);
        sc.setCostAmount(0L);
        sc.setReference(c.getCapitalType());
        open.forEach(p -> {
            p.setStatus(InvestorPayment.CLAIMED);
            p.setClaimCaseId(sc.getId());
        });
        return Optional.of(sc);
    }

    /** "Bezahlen" of a claim: each part with its money reason (no interest, owner decision 2026-10-08). */
    @Transactional
    public ServiceCase payClaim(Savegame sg, Long caseId) {
        ServiceCase sc = openCase(sg, caseId, CaseKind.INVESTOR_CLAIM);
        if (liquidity.available(sg) < sc.getOfferAmount()) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Zahlung nicht.");
        }
        closeCase(sg, sc, CaseStatus.SETTLED, "PAID");
        sc.setPayoutAmount(sc.getOfferAmount());
        InvestorContract c = null;
        for (InvestorPayment p : payments.findByClaimCaseId(sc.getId())) {
            c = p.getContract();
            p.setStatus(InvestorPayment.BOOKED);
            p.setGameTime(sg.getCurrentGameTime());
            p.setPaymentYear(offers.currentYear(sg, facts.latest(sg).orElse(null)));
            p.setInstructionId(outbox.money(sg, -p.getAmount(), reason(p), p.getNote(),
                    new Related(OfferRelated.PAYMENT, p.getId())).getInstructionId());
        }
        diary.addAuto(sg, "CREDIT", "Rückforderung bezahlt", sc.getTitle() + ": "
                + InvestorOfferService.euro(sc.getOfferAmount()) + ".", CASE_RELATED, sc.getId());
        if (c != null) {
            maybeRetire(c);
        }
        return sc;
    }

    /** Overdue claim: one reminder per started overdue month - trust, a payment delay, no interest. */
    void overdue(Savegame sg, ServiceCase sc, long now) {
        int months = (int) ((now - sc.getDeadlineGameTime()) / gameTime.msPerMonth(sg)) + 1;
        if (months <= sc.getRoundsUsed()) {
            return;
        }
        sc.setRoundsUsed(months);
        delays.record(sg, PaymentDelay.INVESTOR_CLAIM, now);
        trust.recordEvent(sc.getCharacter(), cfg().getClaimOverdueTrustDelta(), TrustReason.INVESTOR_CLAIM_OVERDUE,
                sc.getTitle());
        narration.request(sg, NarrationEventType.INVESTOR_CLAIM_REMINDER).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("claim", sc.getOfferAmount()).put("overdueMonths", months).build())
                .category(CommunicationCategory.CREDIT).related(CASE_RELATED, sc.getId())
                .formLink("/bank?case=" + sc.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ I5 end of term

    void announce(Savegame sg, InvestorContract c) {
        c.setAnnounced(true);
        narration.request(sg, NarrationEventType.INVESTOR_END_ANNOUNCEMENT).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("endYear", c.endYear()).put("repayment", c.getAmount())
                        .put("capitalType", InvestorOfferService.capitalText(c)).build())
                .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId())
                .formLink("/bank/investoren").submit();
        if (c.getBreaches() == 0 && c.getExtendedBy() == null && random.chance(cfg().getExtensionProbability())) {
            offers.extensionOffer(sg, c);
        }
    }

    /** Start of the last month: buy-back / repayment when the money suffices, otherwise a claim. */
    void repayment(Savegame sg, InvestorContract c) {
        c.setRepaymentDue(true);
        boolean extended = c.getExtendedBy() != null && contracts.findById(c.getExtendedBy())
                .map(n -> InvestorContract.ACTIVE.equals(n.getStatus())).orElse(false);
        if (extended) {
            return; // the extension replaces the repayment, no money flows
        }
        String note = (c.silent() ? "Rückkauf der Beteiligung " : "Rückzahlung Nachrangdarlehen ")
                + offers.kindLabel(c.getKind());
        if (liquidity.available(sg) >= c.getAmount()) {
            book(sg, c, InvestorPayment.REPAYMENT, c.getAmount(), MoneyReason.INVESTOR_REPAYMENT, note);
            diary.addAuto(sg, "CREDIT", c.silent() ? "Beteiligung zurückgekauft" : "Nachrangdarlehen zurückgezahlt",
                    note + ": " + InvestorOfferService.euro(c.getAmount()) + ".", OfferRelated.CONTRACT, c.getId());
        } else {
            openPayment(sg, c, InvestorPayment.REPAYMENT, c.getAmount(), note);
            claim(sg, c).ifPresent(sc -> diary.addAuto(sg, "CREDIT", "Rückzahlung offen", note + ": Der Kontostand "
                    + "reicht nicht – " + InvestorOfferService.euro(sc.getOfferAmount()) + " sind innerhalb von "
                    + Math.round(cfg().getClaimDays()) + " Tagen fällig.", CASE_RELATED, sc.getId()));
        }
    }

    /** After the last month (and its yearly check): the contract has ended. */
    void finish(Savegame sg, InvestorContract c) {
        c.setStatus(InvestorContract.ENDED);
        c.setEndReason(c.getExtendedBy() != null ? "EXTENDED" : "TERM_END");
        c.setEndedGameTime(sg.getCurrentGameTime());
        if (c.getBreaches() == 0) {
            trust.recordEvent(c.getCharacter(), cfg().getEndTrustDelta(), TrustReason.INVESTOR_CONTRACT_ENDED,
                    offers.kindLabel(c.getKind()));
        }
        if (c.getExtendedBy() == null) {
            narration.request(sg, NarrationEventType.INVESTOR_ENDED).from(c.getCharacter())
                    .facts(NarrationFacts.builder().put("amount", c.getAmount()).put("years", c.getYears()).build())
                    .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId()).submit();
        }
        diary.addAuto(sg, "CREDIT", "Investorenvertrag beendet", c.getCharacter().getName() + " ("
                + offers.kindLabel(c.getKind()) + "): Vertrag über " + InvestorOfferService.euro(c.getAmount())
                + " nach " + c.getYears() + " Jahren beendet" + (c.getExtendedBy() != null ? ", verlängert." : ".")
                + (c.getBreaches() > 0 ? " Vertragsbrüche: " + c.getBreaches() + "." : ""), OfferRelated.CONTRACT,
                c.getId());
        maybeRetire(c);
    }

    /** The investor leaves when nothing is open any more (no running successor, payment, grace or case). */
    void maybeRetire(InvestorContract c) {
        if (InvestorContract.ACTIVE.equals(c.getStatus()) || c.getCharacter() == null) {
            return;
        }
        if (c.getExtendedBy() != null && contracts.findById(c.getExtendedBy())
                .map(n -> InvestorContract.ACTIVE.equals(n.getStatus())).orElse(false)) {
            return;
        }
        boolean openPayment = payments.findByContractOrderByIdAsc(c).stream().anyMatch(InvestorLedger::unpaid);
        boolean grace = obligations.findByContractOrderByIdAsc(c).stream().anyMatch(o -> periods
                .findByObligationOrderByPeriodKeyAsc(o).stream().anyMatch(p -> InvestorPeriod.REMINDED.equals(p.getStatus())));
        if (!openPayment && !grace) {
            offers.retire(c.getCharacter());
        }
    }

    // ------------------------------------------------------------------------------------------ day

    @EventListener
    @Order(89)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (InvestorPeriod p : ledger.reminded(sg)) {
            InvestorObligation o = p.getObligation();
            if (type(o) == InvestorConsideration.A4 && growthReached(sg, o)) {
                o.setFulfilled(true);
                madeUp(sg, p);
            } else if (p.getGraceUntil() != null && p.getGraceUntil() < now) {
                afterGrace(sg, p);
            }
        }
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.INVESTOR_PURCHASE,
                CaseKind.INVESTOR_VISIT, CaseKind.INVESTOR_CLAIM))) {
            if (sc.getStatus() != CaseStatus.AWAITING_PLAYER || sc.getDeadlineGameTime() == null
                    || sc.getDeadlineGameTime() >= now) {
                continue;
            }
            if (sc.getKind() == CaseKind.INVESTOR_CLAIM) {
                overdue(sg, sc, now);
            } else {
                refused(sg, sc, CaseStatus.EXPIRED, "NO_ANSWER");
            }
        }
        dueSoon(sg, now);
    }

    /** A reminder reminder-days-before-end before the end of the period with something still due (mail + in game). */
    void dueSoon(Savegame sg, long now) {
        long month = currentMonth(sg);
        for (InvestorContract c : ledger.active(sg)) {
            for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                if (!InvestorConsideration.DELIVERED.contains(type(o))) {
                    continue;
                }
                InvestorPeriod p = current(o, month);
                long due = dueThisPeriod(o, month);
                if (p == null || p.isRemindedSoon() || due <= 0) {
                    continue;
                }
                long end = periodEnd(sg, p);
                if (now < end - GameTime.days(cfg().getReminderDaysBeforeEnd()) || now >= end) {
                    continue;
                }
                p.setRemindedSoon(true);
                String remaining = type(o) == InvestorConsideration.A1 ? due + " Tiere"
                        : InvestorOfferService.liters(due) + " l";
                narration.request(sg, NarrationEventType.INVESTOR_DUE_SOON).from(c.getCharacter())
                        .facts(NarrationFacts.builder().put("what", offers.describe(o)).put("remaining", remaining).build())
                        .category(CommunicationCategory.CREDIT).related(OfferRelated.CONTRACT, c.getId())
                        .formLink("/bank/investoren").submit();
                String text = "FarmPulse: " + c.getCharacter().getName() + " wartet noch auf " + remaining
                        + (o.getFillType() != null ? " " + labels.label(o.getFillType()) : "");
                outbox.notification(sg, text.length() <= 120 ? text : text.substring(0, 119) + "…", "INFO", end,
                        new Related(OfferRelated.CONTRACT, c.getId()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------ queries

    /** Still to deliver in the current period (tasks, calendar). */
    public record DueItem(Long contractId, Long obligationId, String investor, String type, String fillType,
                          String subType, long remaining, long deadlineGameTime) {
    }

    public List<DueItem> due(Savegame sg) {
        List<DueItem> out = new ArrayList<>();
        long month = currentMonth(sg);
        for (InvestorContract c : ledger.active(sg)) {
            for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                if (!InvestorConsideration.DELIVERED.contains(type(o))) {
                    continue;
                }
                long due = dueThisPeriod(o, month);
                InvestorPeriod p = current(o, month);
                if (due > 0 && p != null) {
                    out.add(new DueItem(c.getId(), o.getId(), c.getCharacter() == null ? null : c.getCharacter().getName(),
                            o.getType(), o.getFillType(), o.getSubType(), due, periodEnd(sg, p)));
                }
            }
        }
        return out;
    }

    /** A known (or estimated) payment of a future month for the liquidity plan (negative = to the investor). */
    public record PlannedPayment(String kind, String label, long amount, boolean estimate) {
    }

    /** I6 liquidity plan: repayment in the last month, R2 / R1 after a term year, open payments next month. */
    public List<PlannedPayment> planned(Savegame sg, long monthIndex) {
        List<PlannedPayment> out = new ArrayList<>();
        long current = currentMonth(sg);
        int period = gameTime.anchor(sg).periodOf(monthIndex);
        Long lastResult = reports.latest(sg).map(r -> r.totals().operatingResult()).orElse(null);
        for (InvestorContract c : ledger.active(sg)) {
            String label = offers.kindLabel(c.getKind());
            boolean extended = c.getExtendedBy() != null;
            if (monthIndex == c.getEndMonthIndex() && !c.isRepaymentDue() && !extended) {
                out.add(new PlannedPayment("INVESTOR_REPAYMENT", label, -c.getAmount(), false));
            }
            if (period == 1 && inTerm(c, monthIndex - 1)) {
                for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                    if (type(o) == InvestorConsideration.R2) {
                        out.add(new PlannedPayment("INVESTOR_PAYOUT", label, -Math.round(c.getAmount() * o.getRate()),
                                false));
                    } else if (type(o) == InvestorConsideration.R1 && lastResult != null && lastResult > 0) {
                        out.add(new PlannedPayment("INVESTOR_PAYOUT", label, -Math.round(lastResult * o.getRate()), true));
                    }
                }
            }
        }
        if (monthIndex == current + 1) {
            for (InvestorPayment p : payments.findBySavegameOrderByIdAsc(sg)) {
                if (InvestorLedger.unpaid(p)) {
                    out.add(new PlannedPayment("INVESTOR_" + p.getKind(), offers.kindLabel(p.getContract().getKind()),
                            -p.getAmount(), false));
                }
            }
            for (InvestorPeriod p : ledger.reminded(sg)) {
                InvestorObligation o = p.getObligation();
                out.add(new PlannedPayment("INVESTOR_COMPENSATION", offers.kindLabel(o.getContract().getKind()),
                        -compensation(sg, o, p), true));
            }
        }
        return out;
    }

    public List<InvestorContract> contracts(Savegame sg) {
        return contracts.findBySavegameOrderByIdDesc(sg).stream()
                .filter(c -> c.getAcceptedGameTime() != null).toList();
    }

    public List<InvestorObligation> obligationsOf(InvestorContract c) {
        return obligations.findByContractOrderByIdAsc(c);
    }

    public List<InvestorPayment> paymentsOf(InvestorContract c) {
        return payments.findByContractOrderByIdAsc(c);
    }

    public List<InvestorPeriod> periodsOf(InvestorObligation o) {
        return periods.findByObligationOrderByPeriodKeyAsc(o);
    }

    public List<InvestorDelivery> deliveriesOf(InvestorObligation o) {
        return deliveries.findByObligationOrderByIdAsc(o);
    }

    public InvestorPeriod currentPeriod(Savegame sg, InvestorObligation o) {
        return current(o, currentMonth(sg));
    }

    public long outstanding(Savegame sg, InvestorObligation o) {
        return outstanding(o, currentMonth(sg));
    }

    /** Related types of the mails and payments (shared with {@link InvestorOfferService}). */
    static final class OfferRelated {
        static final String CONTRACT = InvestorOfferService.CONTRACT_RELATED;
        static final String PAYMENT = InvestorOfferService.PAYMENT_RELATED;

        private OfferRelated() {
        }
    }

    Character investor(InvestorContract c) {
        return c.getCharacter();
    }
}
