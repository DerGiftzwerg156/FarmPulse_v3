package de.farmpulse.rpsim.investor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.CreditConfigResolver;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.diary.PaymentDelayService;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.InvestorContractRepository;
import de.farmpulse.rpsim.repository.InvestorObligationRepository;
import de.farmpulse.rpsim.repository.InvestorPaymentRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.MilestoneRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.2 R32-I1 / R32-I2: offers of large investors (owner decisions 2026-10-08 in QUESTIONS.md).
 * <ul>
 *   <li>I1 Once per FS25 month, at most one offer per FS25 year and at most max-active running investors; only when a
 *   farm report exists with an operating result &gt; 0, no payment delay within delay-lookback-months, no running
 *   call-back, and the credit score of a loan over the amount (credit-term-months, base rate) is at least
 *   min-credit-score. Chance = base + milestone-bonus per milestone (max milestone-bonus-max) + good / - controversial
 *   reputation. Amount: random step of amount-step between amount-min and min(amount-max, asset-share x assets in bank
 *   view); below amount-min no offer. Switched per savegame (settings, events).</li>
 *   <li>The investor ({@code INVESTOR}) is created with the offer; its kind decides the target return and the main
 *   considerations. Half of the offers come as a call (a missed or declined call also brings the mail); answer within
 *   answer-days, an ignored offer costs trust, declining costs nothing.</li>
 *   <li>I2 2-3 packages for the same amount, each with capital type (random), term (min-years..max-years full FS25 years
 *   from the next year start), one main consideration of the kind (different per package, only what the farm can
 *   deliver) and 0-2 side considerations (A2-A4, P1-P5) with their value. Target value = amount x target return x
 *   years; the main consideration takes the rest: goods and milk = rest / today's best market price, animals = rest /
 *   game value per animal, R1 = rest per year / operating result of the last farm report, R2 = rest per year / amount.</li>
 *   <li>Accepting books {@code INVESTOR_CAPITAL}; an extension offer (I5) replaces the repayment, no money flows.</li>
 * </ul>
 */
@Service
public class InvestorOfferService {

    public static final String OFFER_RELATED = "INVESTOR_OFFER";
    public static final String CONTRACT_RELATED = "INVESTOR_CONTRACT";
    public static final String PAYMENT_RELATED = "INVESTOR_PAYMENT";

    private final InvestorContractRepository contracts;
    private final InvestorObligationRepository obligations;
    private final InvestorPaymentRepository payments;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CommunicationRepository communications;
    private final MilestoneRepository milestones;
    private final LoanRepository loans;
    private final FactsService facts;
    private final NeighborService neighbors;
    private final FarmReportService reports;
    private final CreditScoringService scoring;
    private final CreditConfigResolver creditConfigs;
    private final PaymentDelayService delays;
    private final LiquidityService liquidity;
    private final CharacterGeneratorService generator;
    private final CharacterLookup lookup;
    private final VillageReputationService reputation;
    private final PublicActionService publicActions;
    private final TrustScoreService trust;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final InvestorLedger ledger;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public InvestorOfferService(InvestorContractRepository contracts, InvestorObligationRepository obligations,
                                InvestorPaymentRepository payments, ServiceCaseRepository cases,
                                SavegameRepository savegames, CommunicationRepository communications,
                                MilestoneRepository milestones, LoanRepository loans, FactsService facts,
                                NeighborService neighbors, FarmReportService reports, CreditScoringService scoring,
                                CreditConfigResolver creditConfigs, PaymentDelayService delays,
                                LiquidityService liquidity, CharacterGeneratorService generator, CharacterLookup lookup,
                                VillageReputationService reputation, PublicActionService publicActions,
                                TrustScoreService trust, OutboxService outbox, NarrationRequestService narration,
                                DiaryService diary, FallbackTemplates labels, InvestorLedger ledger, RandomSource random,
                                RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.obligations = obligations;
        this.payments = payments;
        this.cases = cases;
        this.savegames = savegames;
        this.communications = communications;
        this.milestones = milestones;
        this.loans = loans;
        this.facts = facts;
        this.neighbors = neighbors;
        this.reports = reports;
        this.scoring = scoring;
        this.creditConfigs = creditConfigs;
        this.delays = delays;
        this.liquidity = liquidity;
        this.generator = generator;
        this.lookup = lookup;
        this.reputation = reputation;
        this.publicActions = publicActions;
        this.trust = trust;
        this.outbox = outbox;
        this.narration = narration;
        this.diary = diary;
        this.labels = labels;
        this.ledger = ledger;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    RpsimProperties.Investor cfg() {
        return props.getFormulas().getInvestor();
    }

    // ------------------------------------------------------------------------------------------ formulas

    /** I1: largest amount = asset-share x assets, rounded down to amount-step, capped at amount-max; 0 below amount-min. */
    long maxAmount(double assets) {
        long step = Math.max(1, cfg().getAmountStep());
        long limit = Math.min(cfg().getAmountMax(), (long) Math.floor(assets * cfg().getAssetShare() / step) * step);
        return limit < cfg().getAmountMin() ? 0 : limit;
    }

    /** I1: chance per month. */
    double probability(int milestoneCount, VillageReputationService.Tier tier) {
        double p = cfg().getBaseProbability()
                + Math.min(cfg().getMilestoneBonusMax(), cfg().getMilestoneBonus() * milestoneCount);
        if (tier == VillageReputationService.Tier.GOOD) {
            p += cfg().getGoodReputationBonus();
        } else if (tier == VillageReputationService.Tier.CONTROVERSIAL) {
            p -= cfg().getControversialReputationMalus();
        }
        return Math.max(0, p);
    }

    /** I3 R1: profit share = value per year / operating result, rounded to profit-share-step, within min..max. */
    double profitShare(double valuePerYear, long operatingResult) {
        if (operatingResult <= 0) {
            return cfg().getProfitShareMax();
        }
        double step = cfg().getProfitShareStep();
        double share = Math.round(valuePerYear / operatingResult / step) * step;
        return round4(Math.max(cfg().getProfitShareMin(), Math.min(cfg().getProfitShareMax(), share)));
    }

    /** I3 R2: payout rate = value per year / amount, rounded to payout-rate-step. */
    double payoutRate(double valuePerYear, long amount) {
        double step = cfg().getPayoutRateStep();
        return round4(Math.round(valuePerYear / amount / step) * step);
    }

    /** Litres rounded to liters-step. */
    long roundLiters(double liters) {
        long step = Math.max(1, cfg().getLitersStep());
        return Math.round(liters / step) * step;
    }

    /** A3: crop-area-share x own area, rounded to crop-area-step, at least crop-area-min. */
    double cropArea(double ownHectares) {
        double step = cfg().getCropAreaStep();
        return Math.max(cfg().getCropAreaMin(), Math.round(ownHectares * cfg().getCropAreaShare() / step) * step);
    }

    static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    /** Today's best market price of a fill type over all sell points (EUR per 1000 l), 0 without a price. */
    static double bestPrice(FarmFacts f, String fillType) {
        if (f == null || f.prices() == null || fillType == null) {
            return 0;
        }
        return f.prices().stream().filter(p -> fillType.equals(p.fillType()) && p.currentPrice() != null)
                .mapToDouble(BridgeDtos.Price::currentPrice).max().orElse(0);
    }

    // ------------------------------------------------------------------------------------------ the farm

    /** What the farm can deliver today (owner decisions: only with an own silo entry, a milk stable, animals ...). */
    record Farm(FarmFacts facts, Map<String, Double> milkCapacity, Map<String, Integer> subTypes,
                Map<String, Double> animalValue, double ownHectares, int animals, boolean holidayFlat,
                Long operatingResult, Set<String> silos) {

        boolean hasAnimals() {
            return animals > 0;
        }

        /** The most common subtype of the farm (ties: the first by name). */
        String mainSubType() {
            return subTypes.entrySet().stream().max(Comparator.comparing((Map.Entry<String, Integer> e) -> e.getValue())
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse(null);
        }

        /** The milk sort of the stables with the largest storage capacity (ties: the first by name). */
        String milkSort() {
            return milkCapacity.entrySet().stream().max(Comparator.comparing((Map.Entry<String, Double> e) -> e.getValue())
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse(null);
        }
    }

    Farm farm(Savegame sg, FarmFacts f) {
        Map<String, Double> milk = new TreeMap<>();
        Map<String, Integer> subTypes = new TreeMap<>();
        Map<String, double[]> values = new TreeMap<>();
        Map<String, double[]> stableValue = new LinkedHashMap<>();
        if (f != null && f.assets() != null && f.assets().animals() != null) {
            for (BridgeDtos.Animal a : f.assets().animals()) {
                if (a != null && a.husbandryUniqueId() != null && a.count() != null && a.count() > 0
                        && a.estimatedValue() != null) {
                    stableValue.computeIfAbsent(a.husbandryUniqueId(), k -> new double[2]);
                    stableValue.get(a.husbandryUniqueId())[0] += a.estimatedValue();
                    stableValue.get(a.husbandryUniqueId())[1] += a.count();
                }
            }
        }
        int animals = 0;
        if (f != null && f.husbandries() != null) {
            for (BridgeDtos.Husbandry h : f.husbandries()) {
                if (h == null) {
                    continue;
                }
                if (h.storage() != null) {
                    for (BridgeDtos.StorageEntry s : h.storage()) {
                        if (s != null && s.fillType() != null && s.capacity() != null && s.capacity() > 0) {
                            milk.merge(s.fillType(), s.capacity(), Double::sum);
                        }
                    }
                }
                if (h.subTypes() != null) {
                    double[] sv = stableValue.get(h.husbandryUniqueId());
                    double perAnimal = sv == null || sv[1] <= 0 ? 0 : sv[0] / sv[1];
                    for (BridgeDtos.SubTypeCount st : h.subTypes()) {
                        if (st != null && st.name() != null && st.count() != null && st.count() > 0) {
                            subTypes.merge(st.name(), st.count(), Integer::sum);
                            animals += st.count();
                            values.computeIfAbsent(st.name(), k -> new double[2]);
                            values.get(st.name())[0] += perAnimal * st.count();
                            values.get(st.name())[1] += st.count();
                        }
                    }
                }
            }
        }
        Map<String, Double> animalValue = new TreeMap<>();
        values.forEach((k, v) -> animalValue.put(k, v[1] <= 0 ? 0 : v[0] / v[1]));
        double hectares = f == null || f.fields() == null ? 0 : f.fields().stream()
                .filter(x -> x != null && x.hectares() != null).mapToDouble(BridgeDtos.Field::hectares).sum();
        boolean holiday = props.getFormulas().getFarmHoliday().isEnabled() && sg.getFarmHolidaySince() != null;
        Long result = reports.latest(sg).map(r -> r.totals().operatingResult()).orElse(null);
        Set<String> silos = f == null ? Set.of() : neighbors.tradeStorage(f).keySet();
        return new Farm(f, milk, subTypes, animalValue, hectares, animals, holiday, result, silos);
    }

    /** W1 / W2 / P2: the sorts of the kind with an own silo entry and a market price. */
    List<String> sorts(Farm farm, RpsimProperties.Investor.Kind kind) {
        return kind.getFillTypes().stream().filter(t -> farm.silos().contains(t) && bestPrice(farm.facts(), t) > 0)
                .toList();
    }

    /** Main considerations of the kind the farm can deliver. */
    List<InvestorConsideration> feasibleMains(Farm farm, RpsimProperties.Investor.Kind kind) {
        List<InvestorConsideration> out = new ArrayList<>();
        for (String m : kind.getMain()) {
            InvestorConsideration c = InvestorConsideration.of(m);
            boolean ok = switch (c) {
                case W1, W2 -> !sorts(farm, kind).isEmpty();
                case W3 -> farm.milkSort() != null && bestPrice(farm.facts(), farm.milkSort()) > 0;
                case A1 -> farm.mainSubType() != null && farm.animalValue().getOrDefault(farm.mainSubType(), 0.0) > 0;
                case R1 -> farm.operatingResult() != null && farm.operatingResult() > 0;
                case R2 -> true;
                default -> false;
            };
            if (ok) {
                out.add(c);
            }
        }
        return out;
    }

    /** Side considerations (A2-A4, P1-P5) the farm can deliver for this kind. */
    List<InvestorConsideration> feasibleSides(Farm farm, RpsimProperties.Investor.Kind kind) {
        List<InvestorConsideration> out = new ArrayList<>();
        for (InvestorConsideration c : InvestorConsideration.SIDE) {
            boolean ok = switch (c) {
                case A2 -> farm.hasAnimals();
                case A3 -> kind.getCrop() != null && farm.ownHectares() > 0;
                case A4 -> farm.ownHectares() > 0 || farm.hasAnimals();
                case P1 -> farm.ownHectares() > 0;
                case P2 -> !sorts(farm, kind).isEmpty();
                case P3 -> farm.holidayFlat();
                case P4, P5 -> true;
                default -> false;
            };
            if (ok) {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ I1 trigger

    @EventListener
    @Order(87)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || !sg.isInvestorsEnabled()) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = currentYear(sg, f);
        if (year == null || year.equals(sg.getInvestorOfferYear()) || !eligible(sg)) {
            return;
        }
        int ms = milestones.findBySavegameOrderByReachedGameTimeAscIdAsc(sg).size();
        if (random.chance(probability(ms, reputation.tier(sg)))) {
            spawnOffer(sg);
        }
    }

    /** I1 conditions apart from the chance and the amount (farm report, delays, call-back, limits). */
    boolean eligible(Savegame sg) {
        if (openOffer(sg).isPresent() || runningInvestors(sg) >= cfg().getMaxActive()) {
            return false;
        }
        Long result = reports.latest(sg).map(r -> r.totals().operatingResult()).orElse(null);
        if (result == null || result <= 0) {
            return false;
        }
        long now = sg.getCurrentGameTime();
        if (delays.any(sg, now - cfg().getDelayLookbackMonths() * gameTime.msPerMonth(sg), now + 1)) {
            return false;
        }
        return loans.findBySavegameAndStatus(sg, LoanStatus.DEFAULTED).isEmpty();
    }

    /** Running investors (an accepted extension does not count twice). */
    public int runningInvestors(Savegame sg) {
        return (int) ledger.active(sg).stream().filter(c -> c.getExtensionOf() == null
                || contracts.findById(c.getExtensionOf()).map(p -> !InvestorContract.ACTIVE.equals(p.getStatus()))
                .orElse(true)).count();
    }

    Optional<ServiceCase> openOffer(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_OFFER)).stream()
                .filter(c -> c.getStatus() == CaseStatus.AWAITING_PLAYER && c.getExternalId() == null).findFirst();
    }

    /** FS25 year of the current month (null without a calendar export). */
    Integer currentYear(Savegame sg, FarmFacts f) {
        return f == null || f.calendar() == null ? null : f.calendar().year();
    }

    /** Month index of period 1 of the next FS25 year. */
    long nextYearStart(Savegame sg) {
        GameTime.Anchor a = gameTime.anchor(sg);
        long cur = a.monthIndex(sg.getCurrentGameTime());
        return cur - (a.periodOf(cur) - 1) + GameTime.PERIODS_PER_YEAR;
    }

    /** One new offer (empty when no amount, score or kind fits). */
    @Transactional
    public Optional<ServiceCase> spawnOffer(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = currentYear(sg, f);
        if (f == null || year == null) {
            return Optional.empty();
        }
        long limit = maxAmount(scoring.totalAssets(sg));
        if (limit <= 0) {
            return Optional.empty();
        }
        long step = Math.max(1, cfg().getAmountStep());
        long amount = cfg().getAmountMin() + step * random.intBetween(0, (int) ((limit - cfg().getAmountMin()) / step));
        RpsimProperties.Credit credit = creditConfigs.forSavegame(sg);
        if (scoring.score(sg, amount, cfg().getCreditTermMonths(), credit.getBaseInterestRate()).finalScore()
                < cfg().getMinCreditScore()) {
            return Optional.empty();
        }
        Farm farm = farm(sg, f);
        Map<String, Double> weights = new LinkedHashMap<>();
        cfg().getKinds().forEach((key, k) -> {
            if (k.getWeight() > 0 && feasibleMains(farm, k).size() >= cfg().getMinPackages()) {
                weights.put(key, k.getWeight());
            }
        });
        if (weights.isEmpty()) {
            return Optional.empty();
        }
        String kindKey = random.weighted(weights);
        List<Draft> drafts = drafts(sg, farm, kindKey, amount, nextYearStart(sg), year + 1);
        if (drafts.size() < cfg().getMinPackages()) {
            return Optional.empty(); // a main quantity rounded to nothing
        }
        Character investor = newInvestor(sg, kindKey);
        ServiceCase sc = offerCase(sg, investor, kindKey, amount, null);
        save(sg, sc, drafts);
        sg.setInvestorOfferYear(year);
        offerMessage(sg, sc, random.chance(cfg().getCallShare()) ? Channel.CALL : Channel.MAIL);
        return Optional.of(sc);
    }

    private ServiceCase offerCase(Savegame sg, Character investor, String kindKey, long amount, Long extensionOf) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.INVESTOR_OFFER);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(investor);
        sc.setReference(kindKey);
        sc.setTitle(kindLabel(kindKey));
        sc.setOfferAmount(amount);
        sc.setExternalId(extensionOf == null ? null : String.valueOf(extensionOf));
        sc.setDirection(extensionOf == null ? null : "EXTENSION"); // shown in the case card
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(Instant.now());
        return cases.save(sc);
    }

    public String kindLabel(String kindKey) {
        RpsimProperties.Investor.Kind k = cfg().getKinds().get(kindKey);
        return k == null || k.getLabel() == null ? kindKey : k.getLabel();
    }

    private Character newInvestor(Savegame sg, String kindKey) {
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(CharacterRole.INVESTOR,
                CharacterCategory.MANDATORY, reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        String kind = kindLabel(kindKey);
        c.setAffiliation(kind);
        c.setShortDescription(c.getName() + " (" + kind + ") – steckt Geld in Höfe und verlangt dafür eine "
                + "Gegenleistung. Gilt als " + c.getTraits() + ".");
        c.setBackstory(c.getShortDescription());
        generator.enrich(c, "Investor/in für " + kind);
        return c;
    }

    private void offerMessage(Savegame sg, ServiceCase sc, Channel channel) {
        boolean extension = sc.getExternalId() != null;
        NarrationFacts.Builder b = NarrationFacts.builder().put("investorKind", sc.getTitle())
                .put("amount", sc.getOfferAmount()).put("packages", sc.getQuantity())
                .put("answerDays", Math.round(cfg().getAnswerDays()));
        if (extension) {
            contracts.findById(Long.valueOf(sc.getExternalId())).ifPresent(c -> b.put("endYear", c.endYear()));
        }
        narration.request(sg, extension ? NarrationEventType.INVESTOR_EXTENSION_OFFER : NarrationEventType.INVESTOR_OFFER)
                .from(sc.getCharacter()).channel(channel).facts(b.build()).category(CommunicationCategory.CREDIT)
                .related(OFFER_RELATED, sc.getId()).formLink("/bank?case=" + sc.getId()).submit();
    }

    /** I1: a missed or declined call keeps the topic open; the offer also comes as a mail while it is open. */
    @EventListener
    @Transactional
    public void onCall(CallService.CallStatusChanged e) {
        if (e.status() != CallStatus.MISSED && e.status() != CallStatus.DECLINED) {
            return;
        }
        communications.findById(e.communicationId())
                .filter(c -> OFFER_RELATED.equals(c.getRelatedEntityType()) && c.getRelatedEntityId() != null)
                .flatMap(c -> cases.findById(c.getRelatedEntityId()))
                .filter(sc -> sc.getKind() == CaseKind.INVESTOR_OFFER && sc.getStatus() == CaseStatus.AWAITING_PLAYER)
                .ifPresent(sc -> offerMessage(sc.getSavegame(), sc, Channel.MAIL));
    }

    // ------------------------------------------------------------------------------------------ I2 packages

    /** A package before it is saved. */
    record Draft(InvestorContract contract, List<InvestorObligation> obligations) {
    }

    /** Saves the packages of an offer case. */
    void save(Savegame sg, ServiceCase sc, List<Draft> drafts) {
        for (Draft d : drafts) {
            InvestorContract c = d.contract();
            c.setCaseId(sc.getId());
            c.setCharacter(sc.getCharacter());
            contracts.save(c);
            for (InvestorObligation o : d.obligations()) {
                o.setSavegame(sg);
                o.setContract(c);
                obligations.save(o);
            }
        }
        sc.setQuantity(drafts.size());
    }

    /** 2-3 packages with different main considerations (fewer when a package cannot be built). */
    List<Draft> drafts(Savegame sg, Farm farm, String kindKey, long amount, long startMonth, int startYear) {
        RpsimProperties.Investor.Kind kind = cfg().getKinds().get(kindKey);
        List<InvestorConsideration> mains = new ArrayList<>(feasibleMains(farm, kind));
        Collections.shuffle(mains, new java.util.Random(random.nextLong()));
        int n = Math.min(mains.size(), random.intBetween(cfg().getMinPackages(), cfg().getMaxPackages()));
        List<Draft> out = new ArrayList<>();
        for (InvestorConsideration main : mains) {
            if (out.size() >= n) {
                break;
            }
            int years = random.intBetween(cfg().getMinYears(), cfg().getMaxYears());
            InvestorContract c = new InvestorContract();
            c.setSavegame(sg);
            c.setKind(kindKey);
            c.setPackageNo(out.size() + 1);
            c.setAmount(amount);
            c.setCapitalType(random.chance(0.5) ? InvestorContract.SILENT : InvestorContract.SUBORDINATED);
            c.setYears(years);
            c.setTargetReturn(kind.getTargetReturn());
            c.setTargetValue(Math.round(amount * kind.getTargetReturn() * years));
            c.setStatus(InvestorContract.OFFERED);
            c.setStartMonthIndex(startMonth);
            c.setEndMonthIndex(startMonth + 12L * years - 1);
            c.setStartYear(startYear);
            c.setOfferedGameTime(sg.getCurrentGameTime());
            List<InvestorObligation> obs = build(farm, kind, c, main);
            if (!obs.isEmpty()) {
                out.add(new Draft(c, obs));
            }
        }
        return out;
    }

    /** Main + 0..max-side-considerations side considerations; empty when the main quantity would round to nothing. */
    List<InvestorObligation> build(Farm farm, RpsimProperties.Investor.Kind kind, InvestorContract c,
                                   InvestorConsideration main) {
        List<InvestorConsideration> pool = new ArrayList<>(feasibleSides(farm, kind));
        Collections.shuffle(pool, new java.util.Random(random.nextLong()));
        int wanted = random.intBetween(0, cfg().getMaxSideConsiderations());
        List<InvestorObligation> sides = new ArrayList<>();
        long sideValue = 0;
        for (InvestorConsideration s : pool) {
            if (sides.size() >= wanted) {
                break;
            }
            InvestorObligation o = side(farm, kind, c, s);
            if (o == null || sideValue + o.getTotalValue() >= c.getTargetValue()) {
                continue;
            }
            sides.add(o);
            sideValue += o.getTotalValue();
        }
        while (true) {
            InvestorObligation m = main(farm, kind, c, main, c.getTargetValue() - sideValue);
            if (m != null) {
                List<InvestorObligation> all = new ArrayList<>();
                all.add(m);
                all.addAll(sides);
                return all;
            }
            if (sides.isEmpty()) {
                return List.of();
            }
            sideValue -= sides.remove(sides.size() - 1).getTotalValue();
        }
    }

    /** The main consideration over {@code rest} EUR of the term; null when its quantity rounds to nothing. */
    InvestorObligation main(Farm farm, RpsimProperties.Investor.Kind kind, InvestorContract c, InvestorConsideration t,
                            long rest) {
        if (rest <= 0) {
            return null;
        }
        int years = c.getYears();
        InvestorObligation o = obligation(t, true, Math.round(rest / (double) years), rest);
        switch (t) {
            case W1 -> {
                String sort = random.pick(sorts(farm, kind));
                double price = bestPrice(farm.facts(), sort);
                long total = roundLiters(rest / price * 1000);
                long perYear = roundLiters(total / (double) years);
                if (total <= 0 || perYear <= 0) {
                    return null;
                }
                o.setFillType(sort);
                o.setUnitPrice(price);
                o.setQuantity(total);
                o.setMinPerYear(perYear);
            }
            case W2, W3 -> {
                String sort = t == InvestorConsideration.W2 ? random.pick(sorts(farm, kind)) : farm.milkSort();
                double price = bestPrice(farm.facts(), sort);
                long perMonth = roundLiters(rest / (years * 12.0) / price * 1000);
                if (perMonth <= 0) {
                    return null;
                }
                o.setFillType(sort);
                o.setUnitPrice(price);
                o.setQuantity(perMonth);
            }
            case A1 -> {
                String sub = farm.mainSubType();
                double value = farm.animalValue().getOrDefault(sub, 0.0);
                long perYear = Math.round(rest / (double) years / value);
                if (perYear <= 0) {
                    return null;
                }
                o.setSubType(sub);
                o.setUnitPrice(value);
                o.setQuantity(perYear);
            }
            case R1 -> o.setRate(profitShare(rest / (double) years, farm.operatingResult()));
            case R2 -> {
                double rate = payoutRate(rest / (double) years, c.getAmount());
                if (rate <= 0) {
                    return null;
                }
                o.setRate(rate);
            }
            default -> {
                return null;
            }
        }
        return o;
    }

    /** A side consideration with its value per year (null when it has none). */
    InvestorObligation side(Farm farm, RpsimProperties.Investor.Kind kind, InvestorContract c, InvestorConsideration t) {
        int years = c.getYears();
        long perYear;
        InvestorObligation o;
        switch (t) {
            case P2 -> {
                String sort = random.pick(sorts(farm, kind));
                double price = bestPrice(farm.facts(), sort);
                long liters = roundLiters(c.getAmount() * cfg().getPurchaseAmountShare() / price * 1000);
                if (liters <= 0) {
                    return null;
                }
                perYear = Math.round(liters / 1000.0 * price * cfg().getPurchaseDiscount());
                o = obligation(t, false, perYear, perYear * years);
                o.setFillType(sort);
                o.setQuantity(liters);
                o.setUnitPrice(price);
            }
            case P3 -> {
                perYear = Math.round(props.getFormulas().getFarmHoliday().getBaseIncomePerMonth()
                        * cfg().getHolidayPeriods().size());
                o = obligation(t, false, perYear, perYear * years);
                o.setQuantity((long) cfg().getHolidayPeriods().size());
            }
            case P4 -> {
                perYear = cfg().getValues().getOrDefault(t.name(), 0L) * cfg().getVisitsPerYear();
                o = obligation(t, false, perYear, perYear * years);
                o.setQuantity((long) cfg().getVisitsPerYear());
            }
            default -> {
                perYear = cfg().getValues().getOrDefault(t.name(), 0L);
                o = obligation(t, false, perYear, perYear * years);
            }
        }
        switch (t) {
            case A2 -> o.setTarget(cfg().getWelfareMinHealth());
            case A3 -> {
                o.setFillType(kind.getCrop());
                o.setHectares(cropArea(farm.ownHectares()));
            }
            case A4 -> {
                if (farm.ownHectares() > 0) {
                    o.setTargetKind("AREA");
                    o.setTarget(Math.ceil(farm.ownHectares() * cfg().getGrowthFactor() * 10) / 10.0);
                } else {
                    o.setTargetKind("ANIMALS");
                    o.setTarget(Math.ceil(farm.animals() * cfg().getGrowthFactor()));
                }
            }
            default -> {
                // no further fields
            }
        }
        return perYear <= 0 ? null : o;
    }

    private static InvestorObligation obligation(InvestorConsideration t, boolean main, long perYear, long total) {
        InvestorObligation o = new InvestorObligation();
        o.setType(t.name());
        o.setMain(main);
        o.setValuePerYear(perYear);
        o.setTotalValue(total);
        return o;
    }

    // ------------------------------------------------------------------------------------------ answer

    ServiceCase open(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.INVESTOR_OFFER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieses Angebot ist nicht mehr offen.");
        }
        return sc;
    }

    public List<InvestorContract> packagesOf(ServiceCase sc) {
        return contracts.findByCaseIdOrderByPackageNoAsc(sc.getId());
    }

    /** "Annehmen" of one package. */
    @Transactional
    public InvestorContract accept(Savegame sg, Long caseId, Long contractId) {
        ServiceCase sc = open(sg, caseId);
        InvestorContract c = packagesOf(sc).stream().filter(p -> p.getId().equals(contractId)).findFirst()
                .orElseThrow(() -> new NotFoundException("package " + contractId));
        InvestorContract previous = sc.getExternalId() == null ? null
                : contracts.findById(Long.valueOf(sc.getExternalId())).orElse(null);
        if (previous == null && runningInvestors(sg) >= cfg().getMaxActive()) {
            throw new BusinessRuleException("INVESTOR_LIMIT", "Es sind höchstens " + cfg().getMaxActive()
                    + " laufende Investoren möglich.");
        }
        if (previous != null && !InvestorContract.ACTIVE.equals(previous.getStatus())) {
            throw new BusinessRuleException("CASE_CLOSED", "Der bisherige Vertrag läuft nicht mehr.");
        }
        long now = sg.getCurrentGameTime();
        if (previous == null) {
            // the term starts with the FS25 year after the acceptance (the offer may have crossed a year change)
            long start = nextYearStart(sg);
            int shift = (int) ((start - c.getStartMonthIndex()) / 12);
            c.setStartMonthIndex(start);
            c.setEndMonthIndex(start + 12L * c.getYears() - 1);
            c.setStartYear(c.getStartYear() + shift);
        }
        c.setStatus(InvestorContract.ACTIVE);
        c.setAcceptedGameTime(now);
        for (InvestorContract other : packagesOf(sc)) {
            if (!other.getId().equals(c.getId())) {
                other.setStatus(InvestorContract.DISCARDED);
            }
        }
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("ACCEPTED");
        sc.setClosedAtGameTime(now);
        List<InvestorObligation> obs = obligations.findByContractOrderByIdAsc(c);
        String what = describe(obs);
        Character investor = c.getCharacter();
        if (previous != null) {
            previous.setExtendedBy(c.getId());
            diary.addAuto(sg, "CREDIT", "Investorenvertrag verlängert", investor.getName() + " (" + kindLabel(c.getKind())
                    + ") lässt " + euro(c.getAmount()) + " für weitere " + c.getYears() + " Jahre im Hof. Gegenleistung: "
                    + what + ".", CONTRACT_RELATED, c.getId());
        } else {
            InvestorPayment p = new InvestorPayment();
            p.setSavegame(sg);
            p.setContract(c);
            p.setKind(InvestorPayment.CAPITAL);
            p.setAmount(c.getAmount());
            p.setPaymentYear(currentYear(sg, facts.latest(sg).orElse(null)));
            p.setGameTime(now);
            p.setStatus(InvestorPayment.BOOKED);
            payments.save(p);
            p.setInstructionId(outbox.money(sg, c.getAmount(), MoneyReason.INVESTOR_CAPITAL, "Investorenkapital "
                    + kindLabel(c.getKind()), new Related(PAYMENT_RELATED, p.getId())).getInstructionId());
            diary.addAuto(sg, "CREDIT", "Investor eingestiegen", investor.getName() + " (" + kindLabel(c.getKind())
                    + ") investiert " + euro(c.getAmount()) + " als " + capitalText(c) + " für " + c.getYears()
                    + " Jahre ab dem Jahr " + c.getStartYear() + ". Gegenleistung: " + what + ".", CONTRACT_RELATED,
                    c.getId());
            lookup.bank(sg).ifPresent(bank -> narration.request(sg, NarrationEventType.INVESTOR_BANK_NOTE).from(bank)
                    .facts(NarrationFacts.builder().put("investorKind", kindLabel(c.getKind())).put("amount", c.getAmount())
                            .put("capitalType", capitalText(c)).put("years", c.getYears())
                            .put("bankEffect", c.silent()
                                    ? "Eine stille Beteiligung zählt bei uns wie eingezahltes Eigenkapital – Ihre "
                                    + "Eigenkapitalquote steigt."
                                    : "Ein Nachrangdarlehen zählt bei uns als Schuld – Ihre Eigenkapitalquote sinkt.")
                            .build())
                    .category(CommunicationCategory.CREDIT).related(CONTRACT_RELATED, c.getId()).submit());
        }
        obs.stream().filter(o -> InvestorConsideration.P5.name().equals(o.getType())).findFirst().ifPresent(o -> {
            RpsimProperties.Investor.Kind kind = cfg().getKinds().get(c.getKind());
            double delta = kind == null ? 0 : kind.getReputationDelta();
            if (delta != 0) {
                publicActions.record(sg, PublicActionType.INVESTOR_NAMED, delta,
                        kindLabel(c.getKind()) + " beteiligt sich am Hof");
            }
        });
        narration.request(sg, NarrationEventType.INVESTOR_ACCEPTED).from(investor)
                .facts(NarrationFacts.builder().put("amount", c.getAmount()).put("capitalType", capitalText(c))
                        .put("years", c.getYears()).put("startYear", c.getStartYear()).put("considerations", what).build())
                .category(CommunicationCategory.CREDIT).related(CONTRACT_RELATED, c.getId()).submit();
        return c;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        close(sg, sc, CaseStatus.DECLINED, "PLAYER", InvestorContract.DECLINED);
        return sc;
    }

    /** Daily: an offer left unanswered until its deadline lapses and costs trust with the investor. */
    @EventListener
    @Order(86)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_OFFER))) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                trust.recordEvent(sc.getCharacter(), cfg().getIgnoredTrustDelta(), TrustReason.INVESTOR_OFFER_IGNORED,
                        "Angebot über " + euro(sc.getOfferAmount()));
                close(sg, sc, CaseStatus.EXPIRED, "NO_ANSWER", InvestorContract.EXPIRED);
            }
        }
    }

    private void close(Savegame sg, ServiceCase sc, CaseStatus status, String resolution, String contractStatus) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        packagesOf(sc).forEach(c -> c.setStatus(contractStatus));
        if (sc.getExternalId() == null) {
            retire(sc.getCharacter()); // an extension offer leaves the running contract (and its investor) as it is
        }
    }

    // ------------------------------------------------------------------------------------------ I5 extension

    /** I5: an extension offer with new packages over the same amount (does not count against one offer per year). */
    @Transactional
    public Optional<ServiceCase> extensionOffer(Savegame sg, InvestorContract previous) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Farm farm = farm(sg, f);
        RpsimProperties.Investor.Kind kind = cfg().getKinds().get(previous.getKind());
        if (f == null || kind == null || feasibleMains(farm, kind).size() < cfg().getMinPackages()) {
            return Optional.empty();
        }
        List<Draft> drafts = drafts(sg, farm, previous.getKind(), previous.getAmount(), previous.getEndMonthIndex() + 1,
                previous.endYear() + 1);
        if (drafts.size() < cfg().getMinPackages()) {
            return Optional.empty();
        }
        drafts.forEach(d -> d.contract().setExtensionOf(previous.getId()));
        ServiceCase sc = offerCase(sg, previous.getCharacter(), previous.getKind(), previous.getAmount(), previous.getId());
        save(sg, sc, drafts);
        offerMessage(sg, sc, Channel.MAIL);
        return Optional.of(sc);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** The investor of a finished offer or contract leaves (owner decision 2026-10-08, like the bulk buyers). */
    void retire(Character c) {
        if (c == null || c.getStatus() == CharacterStatus.TERMINATED) {
            return;
        }
        c.setStatus(CharacterStatus.TERMINATED);
        c.setTerminationReason(TerminationReason.CONTRACT_ENDED);
        c.setLeftAtGameTime(c.getSavegame().getCurrentGameTime());
    }

    static String capitalText(InvestorContract c) {
        return c.silent() ? "stille Beteiligung" : "Nachrangdarlehen";
    }

    /** Short German text of the considerations (mail, diary). */
    String describe(List<InvestorObligation> obs) {
        List<String> parts = new ArrayList<>();
        for (InvestorObligation o : obs) {
            parts.add(describe(o));
        }
        return String.join(", ", parts);
    }

    String describe(InvestorObligation o) {
        String sort = o.getFillType() == null ? "" : labels.label(o.getFillType());
        return switch (InvestorConsideration.of(o.getType())) {
            case W1 -> liters(o.getQuantity()) + " l " + sort + " über die Laufzeit (mindestens "
                    + liters(o.getMinPerYear()) + " l je Jahr)";
            case W2 -> liters(o.getQuantity()) + " l " + sort + " je Monat";
            case W3 -> liters(o.getQuantity()) + " l " + sort + " je Monat aus dem Stall";
            case R1 -> percent(o.getRate()) + " des Betriebsergebnisses je Jahr";
            case R2 -> percent(o.getRate()) + " der Summe je Jahr als Ausschüttung";
            case A1 -> o.getQuantity() + " Tiere (" + labels.label(o.getSubType()) + ") je Jahr";
            case A2 -> "Tierwohl: Stallgesundheit im Monatsmittel mindestens " + Math.round(o.getTarget()) + " %";
            case A3 -> String.format(Locale.GERMANY, "%.1f", o.getHectares()) + " ha " + sort + " je Erntejahr";
            case A4 -> "AREA".equals(o.getTargetKind())
                    ? "Hof-Fläche mindestens " + String.format(Locale.GERMANY, "%.1f", o.getTarget()) + " ha bis Ende des ersten Jahres"
                    : "Tierbestand mindestens " + Math.round(o.getTarget()) + " bis Ende des ersten Jahres";
            case P1 -> "Vetorecht beim Feldverkauf";
            case P2 -> "Vorkaufsrecht auf " + liters(o.getQuantity()) + " l " + sort + " je Jahr";
            case P3 -> "Ferienwohnung im Juli und August";
            case P4 -> o.getQuantity() + " Besuch(e) je Jahr";
            case P5 -> "Namensnennung im Dorfblatt";
        };
    }

    static String liters(Long liters) {
        return String.format(Locale.GERMANY, "%,d", liters == null ? 0 : liters);
    }

    static String percent(Double rate) {
        return String.format(Locale.GERMANY, "%.1f %%", (rate == null ? 0 : rate) * 100);
    }

    static String euro(long amount) {
        return String.format(Locale.GERMANY, "%,d €", amount);
    }

    public List<ServiceCase> offers(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.INVESTOR_OFFER));
    }

    /** Liquidity available now (claims, repayment). */
    long available(Savegame sg) {
        return liquidity.available(sg);
    }
}
