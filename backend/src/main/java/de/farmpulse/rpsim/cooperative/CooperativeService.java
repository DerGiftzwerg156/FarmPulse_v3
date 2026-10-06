package de.farmpulse.rpsim.cooperative;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CoopPriceYear;
import de.farmpulse.rpsim.domain.CoopShareNotice;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.CoopPriceYearRepository;
import de.farmpulse.rpsim.repository.CoopShareNoticeRepository;
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
 * Roadmap V3.1 R31-D7 (owner decisions 2026-10-05): shares of the cooperative (card in "Handel").
 * <ul>
 *   <li>Shares of {@code share-price} (at most {@code max-shares}) bought by form ({@code COOP_SHARES}); a cancellation is
 *   repaid at the nominal value after {@code notice-months} (the shares earn their dividend until then).</li>
 *   <li>Dividend at the start of {@code dividend-period}: rate = {@code base-dividend-rate} × price index (per fill type
 *   the mean of the daily mean prices of the FS25 year that ended ÷ that of the year before, averaged over the fill
 *   types of both years; the base rate without a year before), capped {@code min-rate}..{@code max-rate}; an accepted
 *   DIVIDEND_UP adds its points once (within the cap).</li>
 *   <li>General assembly at the start of {@code coop-assembly.period} for members: a random topic, the player votes by
 *   button or in-game question within {@code answer-days} with the shares as votes; each active character adds
 *   {@code character-votes} votes and votes yes with {@code yes-base} + trust /
 *   {@code yes-trust-divisor}; the topic passes with at least half of the votes. At the assembly a member with
 *   {@code coop-board.min-shares} shares and trust of the cooperative from {@code min-trust} is elected to the board.</li>
 *   <li>Board: rumours earlier and more forward-contract quantity (MarketEventEngine, ForwardContractService),
 *   {@code reputation-per-year} at the dividend, a meeting at the start of each {@code meeting-periods} (case
 *   COOP_BOARD_MEETING in "Kalender"); a missed meeting costs trust of the cooperative, {@code removed-after-missed}
 *   missed meetings remove the player.</li>
 * </ul>
 */
@Service
public class CooperativeService {

    public static final String RELATED = "COOPERATIVE";
    static final String BOARD_ELECTION = "BOARD_ELECTION";

    private final CoopShareNoticeRepository notices;
    private final CoopPriceYearRepository priceYears;
    private final ServiceCaseRepository cases;
    private final CharacterRepository characters;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final VillageNewsService news;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public CooperativeService(CoopShareNoticeRepository notices, CoopPriceYearRepository priceYears,
                              ServiceCaseRepository cases, CharacterRepository characters, SavegameRepository savegames,
                              FactsService facts, LiquidityService liquidity, OutboxService outbox, ServiceRoleService roles,
                              NarrationRequestService narration, TrustScoreService trust, PublicActionService publicActions,
                              VillageNewsService news, DiaryService diary, FallbackTemplates labels, RandomSource random,
                              GameTime gameTime, RpsimProperties props) {
        this.notices = notices;
        this.priceYears = priceYears;
        this.cases = cases;
        this.characters = characters;
        this.savegames = savegames;
        this.facts = facts;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.news = news;
        this.diary = diary;
        this.labels = labels;
        this.random = random;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.CoopShares cfg() {
        return props.getFormulas().getCoopShares();
    }

    private RpsimProperties.CoopAssembly assembly() {
        return props.getFormulas().getCoopAssembly();
    }

    private RpsimProperties.CoopBoard board() {
        return props.getFormulas().getCoopBoard();
    }

    private Character cooperative(Savegame sg) {
        return roles.ensure(sg, CharacterRole.COOPERATIVE);
    }

    // ------------------------------------------------------------------------------------------ shares

    /** Shares already cancelled and not repaid yet. */
    public int noticed(Savegame sg) {
        return notices.findBySavegameOrderByIdAsc(sg).stream().filter(n -> n.getPaidGameTime() == null)
                .mapToInt(CoopShareNotice::getShares).sum();
    }

    @Transactional
    public Savegame buy(Savegame sg, int count) {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("COOP_DISABLED", "Genossenschaftsanteile sind abgeschaltet.");
        }
        if (count <= 0 || sg.getCoopShares() + count > cfg().getMaxShares()) {
            throw new BusinessRuleException("COOP_SHARES_LIMIT", "Höchstens " + cfg().getMaxShares() + " Anteile je Hof.");
        }
        long amount = count * cfg().getSharePrice();
        if (liquidity.available(sg) < amount) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Anteile nicht.");
        }
        sg.setCoopShares(sg.getCoopShares() + count);
        Character coop = cooperative(sg);
        outbox.money(sg, -amount, MoneyReason.COOP_SHARES, count + " Genossenschaftsanteile", new Related(RELATED, sg.getId()));
        narration.request(sg, NarrationEventType.COOP_SHARES_CONFIRMED).from(coop)
                .facts(NarrationFacts.builder().put("shares", count).put("amount", amount)
                        .put("totalShares", sg.getCoopShares()).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sg.getId()).submit();
        diary.addAuto(sg, "TRADE", "Genossenschaftsanteile gezeichnet", count + " Anteile für " + amount + " €.",
                RELATED, sg.getId());
        return sg;
    }

    /** Cancels shares; repaid at the nominal value after the notice period. */
    @Transactional
    public CoopShareNotice cancel(Savegame sg, int count) {
        if (count <= 0 || count > sg.getCoopShares() - noticed(sg)) {
            throw new BusinessRuleException("COOP_SHARES_NOTICE", "So viele ungekündigte Anteile hast du nicht.");
        }
        long now = sg.getCurrentGameTime();
        CoopShareNotice n = new CoopShareNotice();
        n.setSavegame(sg);
        n.setShares(count);
        n.setNoticedGameTime(now);
        n.setDueGameTime(gameTime.addMonths(sg, now, cfg().getNoticeMonths()));
        notices.save(n);
        narration.request(sg, NarrationEventType.COOP_SHARES_NOTICE).from(cooperative(sg))
                .facts(NarrationFacts.builder().put("shares", count).put("amount", count * cfg().getSharePrice())
                        .put("noticeMonths", cfg().getNoticeMonths()).build())
                .category(CommunicationCategory.TRADE).related(RELATED, n.getId()).submit();
        return n;
    }

    public List<CoopShareNotice> notices(Savegame sg) {
        return notices.findBySavegameOrderByIdAsc(sg);
    }

    // ------------------------------------------------------------------------------------------ prices

    /** Once per game day: the mean price of every fill type of the latest export into the year of the calendar. */
    @EventListener
    @Order(90)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        facts.latest(sg).ifPresent(f -> samplePrices(sg, f));
        expireCases(sg);
    }

    @Transactional
    public void samplePrices(Savegame sg, FarmFacts f) {
        Integer year = sg.getCalYear();
        if (year == null || f.prices() == null) {
            return;
        }
        long day = GameTime.dayIndex(sg.getCurrentGameTime());
        Map<String, double[]> mean = new HashMap<>();
        for (BridgeDtos.Price p : f.prices()) {
            if (p.fillType() != null && p.currentPrice() != null && p.currentPrice() > 0) {
                double[] a = mean.computeIfAbsent(p.fillType(), k -> new double[2]);
                a[0] += p.currentPrice();
                a[1]++;
            }
        }
        mean.forEach((ft, a) -> {
            CoopPriceYear y = priceYears.findBySavegameAndCropYearAndFillType(sg, year, ft).orElseGet(() -> {
                CoopPriceYear n = new CoopPriceYear();
                n.setSavegame(sg);
                n.setCropYear(year);
                n.setFillType(ft);
                n.setLastDay(-1);
                return n;
            });
            if (y.getLastDay() == day) {
                return;
            }
            y.setLastDay(day);
            y.setPriceSum(y.getPriceSum() + a[0] / a[1]);
            y.setSamples(y.getSamples() + 1);
            priceYears.save(y);
        });
    }

    /** Price index of a year against the year before; empty without both years. */
    public Optional<Double> priceIndex(Savegame sg, int year) {
        Map<String, Double> now = means(sg, year);
        Map<String, Double> before = means(sg, year - 1);
        double sum = 0;
        int n = 0;
        for (Map.Entry<String, Double> e : now.entrySet()) {
            Double b = before.get(e.getKey());
            if (b != null && b > 0) {
                sum += e.getValue() / b;
                n++;
            }
        }
        return n == 0 ? Optional.empty() : Optional.of(sum / n);
    }

    private Map<String, Double> means(Savegame sg, int year) {
        Map<String, Double> out = new HashMap<>();
        for (CoopPriceYear y : priceYears.findBySavegameAndCropYear(sg, year)) {
            if (y.getSamples() > 0) {
                out.put(y.getFillType(), y.getPriceSum() / y.getSamples());
            }
        }
        return out;
    }

    /** Dividend rate for the year that ended (bonus of an accepted DIVIDEND_UP included). */
    public double dividendRate(Savegame sg, Integer endedYear) {
        double rate = cfg().getBaseDividendRate();
        if (endedYear != null) {
            rate *= priceIndex(sg, endedYear).orElse(1.0);
        }
        rate = Math.max(cfg().getMinRate(), Math.min(cfg().getMaxRate(), rate));
        return Math.min(cfg().getMaxRate(), rate + sg.getCoopDividendBonus());
    }

    // ------------------------------------------------------------------------------------------ month start

    @EventListener
    @Order(90)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled()) {
            return;
        }
        long now = sg.getCurrentGameTime();
        for (CoopShareNotice n : notices.findBySavegameOrderByIdAsc(sg)) {
            if (n.getPaidGameTime() == null && n.getDueGameTime() <= now) {
                repay(sg, n);
            }
        }
        int period = gameTime.periodOfYear(sg, now);
        if (period == cfg().getDividendPeriod()) {
            dividend(sg);
        }
        if (period == assembly().getPeriod() && sg.getCoopShares() > 0) {
            invite(sg);
        }
        if (sg.isCoopBoard() && board().getMeetingPeriods().contains(period)) {
            meeting(sg);
        }
    }

    private void repay(Savegame sg, CoopShareNotice n) {
        int count = Math.min(n.getShares(), sg.getCoopShares());
        long amount = count * cfg().getSharePrice();
        n.setPaidGameTime(sg.getCurrentGameTime());
        sg.setCoopShares(sg.getCoopShares() - count);
        if (amount > 0) {
            outbox.money(sg, amount, MoneyReason.COOP_SHARES, "Rückzahlung " + count + " Genossenschaftsanteile",
                    new Related(RELATED, n.getId()));
            narration.request(sg, NarrationEventType.COOP_SHARES_REPAID).from(cooperative(sg))
                    .facts(NarrationFacts.builder().put("shares", count).put("amount", amount).build())
                    .category(CommunicationCategory.TRADE).related(RELATED, n.getId()).submit();
        }
    }

    /** Year change: dividend of the year that ended and the board's reputation. */
    @Transactional
    public long dividend(Savegame sg) {
        Integer ended = sg.getCalYear() == null ? null : sg.getCalYear() - 1;
        double rate = dividendRate(sg, ended);
        sg.setCoopDividendBonus(0);
        if (sg.isCoopBoard()) {
            publicActions.record(sg, PublicActionType.COOPERATIVE, board().getReputationPerYear(), "Vorstand der Genossenschaft");
        }
        long amount = Math.round(sg.getCoopShares() * cfg().getSharePrice() * rate);
        if (amount <= 0) {
            return 0;
        }
        outbox.money(sg, amount, MoneyReason.COOP_DIVIDEND, "Dividende der Genossenschaft", new Related(RELATED, sg.getId()));
        narration.request(sg, NarrationEventType.COOP_DIVIDEND).from(cooperative(sg))
                .facts(NarrationFacts.builder().put("shares", sg.getCoopShares())
                        .put("ratePercent", Math.round(rate * 1000) / 10.0).put("amount", amount).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sg.getId()).submit();
        return amount;
    }

    // ------------------------------------------------------------------------------------------ assembly

    boolean boardEligible(Savegame sg) {
        return !sg.isCoopBoard() && sg.getCoopShares() >= board().getMinShares()
                && cooperative(sg).getTrustScore() >= board().getMinTrust();
    }

    @Transactional
    public Optional<ServiceCase> invite(Savegame sg) {
        if (assembly().getTopics().isEmpty() || open(sg, CaseKind.COOP_ASSEMBLY).isPresent()) {
            return Optional.empty();
        }
        long now = sg.getCurrentGameTime();
        String topic = random.pick(assembly().getTopics());
        boolean election = boardEligible(sg);
        Character coop = cooperative(sg);
        ServiceCase sc = newCase(sg, CaseKind.COOP_ASSEMBLY, coop, now + GameTime.days(assembly().getAnswerDays()));
        sc.setReference(topic);
        sc.setDirection(election ? BOARD_ELECTION : null);
        narration.request(sg, NarrationEventType.COOP_ASSEMBLY_INVITATION).from(coop)
                .facts(NarrationFacts.builder().put("topic", topic).put("boardElection", election)
                        .put("answerDays", Math.round(assembly().getAnswerDays())).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                .formLink("/kalender?case=" + sc.getId()).submit();
        return Optional.of(sc);
    }

    private ServiceCase newCase(Savegame sg, CaseKind kind, Character c, long deadline) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(c);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(deadline);
        sc.setCreatedAt(Instant.now());
        return cases.save(sc);
    }

    private Optional<ServiceCase> open(Savegame sg, CaseKind kind) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(kind)).stream()
                .filter(c -> c.getStatus() == CaseStatus.AWAITING_PLAYER).findFirst();
    }

    private ServiceCase own(Savegame sg, Long id, CaseKind kind) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != kind || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    /** "Ja" / "Nein"; {@code null} = no vote (the deadline passed). */
    @Transactional
    public ServiceCase vote(Savegame sg, Long id, Boolean yes) {
        ServiceCase sc = own(sg, id, CaseKind.COOP_ASSEMBLY);
        return result(sg, sc, yes);
    }

    /** Tally: the player's shares (if voted) plus the votes of the village characters. */
    ServiceCase result(Savegame sg, ServiceCase sc, Boolean playerYes) {
        double yesVotes = 0;
        double total = 0;
        if (playerYes != null) {
            total += sg.getCoopShares();
            yesVotes += playerYes ? sg.getCoopShares() : 0;
        }
        for (Character c : characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE)) {
            double p = Math.max(0, Math.min(1, assembly().getYesBase() + c.getTrustScore() / assembly().getYesTrustDivisor()));
            total += assembly().getCharacterVotes();
            if (random.chance(p)) {
                yesVotes += assembly().getCharacterVotes();
            }
        }
        boolean accepted = total > 0 && yesVotes / total >= 0.5;
        String topic = sc.getReference();
        sc.setStatus(playerYes == null ? CaseStatus.EXPIRED : CaseStatus.SETTLED);
        sc.setResolution(accepted ? "ACCEPTED" : "REJECTED");
        sc.setQuantity((int) Math.round(total == 0 ? 0 : yesVotes / total * 100));
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        if (accepted) {
            switch (topic) {
                case "DIVIDEND_UP" -> sg.setCoopDividendBonus(sg.getCoopDividendBonus() + assembly().getDividendUpPoints());
                case "GRAIN_STORE" -> sg.setCoopGrainStore(true);
                case "FESTIVAL_SPONSORING" -> publicActions.record(sg, PublicActionType.COOPERATIVE,
                        assembly().getFestivalSponsoringReputation(), "Genossenschaft sponsert die Dorffeste");
                default -> {
                }
            }
        }
        narration.request(sg, NarrationEventType.COOP_ASSEMBLY_RESULT).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("topic", topic).put("yesPercent", sc.getQuantity())
                        .put("result", accepted ? "angenommen" : "abgelehnt").build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit();
        news.add(sg, VillageNewsService.Section.VILLAGE, "COOP_ASSEMBLY", "Generalversammlung der Genossenschaft: "
                + labels.label(topic) + " wurde mit " + sc.getQuantity() + " % Ja-Stimmen " + (accepted ? "angenommen." : "abgelehnt."));
        if (BOARD_ELECTION.equals(sc.getDirection()) && boardEligible(sg)) {
            sg.setCoopBoard(true);
            sg.setCoopBoardMissed(0);
            narration.request(sg, NarrationEventType.COOP_BOARD_ELECTED).from(sc.getCharacter())
                    .facts(NarrationFacts.builder().build())
                    .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit();
            diary.addAuto(sg, "ROTATION", "In den Vorstand gewählt", "Die Generalversammlung wählt den Hof in den Vorstand "
                    + "der Genossenschaft.", RELATED, sc.getId());
        }
        return sc;
    }

    // ------------------------------------------------------------------------------------------ board

    @Transactional
    public Optional<ServiceCase> meeting(Savegame sg) {
        if (open(sg, CaseKind.COOP_BOARD_MEETING).isPresent()) {
            return Optional.empty();
        }
        Character coop = cooperative(sg);
        ServiceCase sc = newCase(sg, CaseKind.COOP_BOARD_MEETING, coop,
                sg.getCurrentGameTime() + GameTime.days(board().getMeetingAnswerDays()));
        narration.request(sg, NarrationEventType.COOP_BOARD_MEETING).from(coop)
                .facts(NarrationFacts.builder().put("answerDays", Math.round(board().getMeetingAnswerDays())).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                .formLink("/kalender?case=" + sc.getId()).submit();
        return Optional.of(sc);
    }

    /** "Teilnehmen". */
    @Transactional
    public ServiceCase attend(Savegame sg, Long id) {
        ServiceCase sc = own(sg, id, CaseKind.COOP_BOARD_MEETING);
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("ATTENDED");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        diary.addAuto(sg, "ROTATION", "Vorstandssitzung", "Teilnahme an der Sitzung des Genossenschaftsvorstands.",
                RELATED, sc.getId());
        return sc;
    }

    /** "Absagen" (or no answer): a missed meeting. */
    @Transactional
    public ServiceCase skip(Savegame sg, Long id) {
        return missed(sg, own(sg, id, CaseKind.COOP_BOARD_MEETING), CaseStatus.DECLINED);
    }

    private ServiceCase missed(Savegame sg, ServiceCase sc, CaseStatus status) {
        sc.setStatus(status);
        sc.setResolution("MISSED");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        trust.recordEvent(sc.getCharacter(), board().getMissedTrustDelta(), TrustReason.COOP_BOARD_MISSED,
                "Vorstandssitzung verpasst");
        sg.setCoopBoardMissed(sg.getCoopBoardMissed() + 1);
        if (sg.isCoopBoard() && sg.getCoopBoardMissed() >= board().getRemovedAfterMissed()) {
            sg.setCoopBoard(false);
            narration.request(sg, NarrationEventType.COOP_BOARD_REMOVED).from(sc.getCharacter())
                    .facts(NarrationFacts.builder().put("missed", sg.getCoopBoardMissed()).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit();
            sg.setCoopBoardMissed(0);
        }
        return sc;
    }

    private void expireCases(Savegame sg) {
        long now = sg.getCurrentGameTime();
        open(sg, CaseKind.COOP_ASSEMBLY).filter(sc -> sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now)
                .ifPresent(sc -> result(sg, sc, null));
        open(sg, CaseKind.COOP_BOARD_MEETING).filter(sc -> sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now)
                .ifPresent(sc -> missed(sg, sc, CaseStatus.EXPIRED));
    }
}
