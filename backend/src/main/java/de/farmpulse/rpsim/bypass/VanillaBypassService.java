package de.farmpulse.rpsim.bypass;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
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
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandBypassEvent;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-D: the vanilla loan, the FS25 field menu and helpers without employee are not locked (V1 decision
 * "recognise and warn, switch nothing off") - the characters react instead.
 * <ul>
 *   <li>D1 - vanilla loan: every export compares {@code liabilities.vanillaLoan.remainingAmount} with the last one;
 *   increases / repayments are collected and answered by the bank advisor once per game day. From the second loan
 *   while one is open new credits cost {@code loan-interest-surcharge} more until the vanilla loan is repaid.</li>
 *   <li>D2 - field menu: a field of a character bought over their head costs trust and village reputation, the former
 *   owner claims {@code compensation-share} of the game price (pay / refuse). A free field: diary only. An own field
 *   sold in the menu: diary and village gossip.</li>
 *   <li>D3 - helpers without employee: one hint of the cooperative with a link to "post a job".</li>
 * </ul>
 * Everything is switched off by {@code vanilla-bypass.enabled} or per savegame (settings page).
 */
@Service
public class VanillaBypassService {

    public static final String RELATED = "COMPENSATION";

    private final SavegameRepository savegames;
    private final FactsService facts;
    private final CharacterLookup lookup;
    private final CharacterRepository characters;
    private final ServiceCaseRepository cases;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final OutboxService outbox;
    private final RandomSource random;
    private final RpsimProperties props;

    public VanillaBypassService(SavegameRepository savegames, FactsService facts, CharacterLookup lookup,
                                CharacterRepository characters, ServiceCaseRepository cases,
                                NarrationRequestService narration, TrustScoreService trust,
                                PublicActionService publicActions, DiaryService diary, OutboxService outbox,
                                RandomSource random, RpsimProperties props) {
        this.savegames = savegames;
        this.facts = facts;
        this.lookup = lookup;
        this.characters = characters;
        this.cases = cases;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.outbox = outbox;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.VanillaBypass cfg() {
        return props.getFormulas().getVanillaBypass();
    }

    public boolean active(Savegame sg) {
        return cfg().isEnabled() && sg.isVanillaBypassEnabled();
    }

    // ------------------------------------------------------------------------------------------ D1 vanilla loan

    /**
     * Collects the change of the vanilla loan since the last export. The first export and a rewound game time (reload
     * without saving) only set the reference point.
     */
    @EventListener
    @Order(20)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.liabilities() == null || f.liabilities().vanillaLoan() == null) {
            return;
        }
        double remaining = facts.vanillaLoanRemaining(f);
        Double seen = sg.getVanillaLoanSeen();
        Long seenAt = sg.getVanillaLoanSeenGameTime();
        if (seen != null && seenAt != null && e.gameTime() >= seenAt) {
            double delta = remaining - seen;
            if (delta > 0) {
                sg.setVanillaLoanPendingTaken(sg.getVanillaLoanPendingTaken() + delta);
            } else if (delta < 0) {
                sg.setVanillaLoanPendingRepaid(sg.getVanillaLoanPendingRepaid() - delta);
            }
        } else if (seenAt != null && e.gameTime() < seenAt) {
            sg.setVanillaLoanPendingTaken(0);
            sg.setVanillaLoanPendingRepaid(0);
        }
        sg.setVanillaLoanSeen(remaining);
        sg.setVanillaLoanSeenGameTime(e.gameTime());
        outsideHelpers(sg, f);
    }

    /** Once per game day: the bank answers the collected loan / repayment. */
    @EventListener
    @Order(21)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        loanReactions(sg);
        expireClaims(sg);
    }

    @Transactional
    public void loanReactions(Savegame sg) {
        double taken = sg.getVanillaLoanPendingTaken();
        double repaid = sg.getVanillaLoanPendingRepaid();
        double remaining = sg.getVanillaLoanSeen() == null ? 0 : sg.getVanillaLoanSeen();
        if (taken >= cfg().getLoanMinIncrease()) {
            sg.setVanillaLoanPendingTaken(0);
            loanTaken(sg, Math.round(taken), Math.round(remaining));
        }
        if (repaid >= cfg().getLoanMinRepayment()) {
            sg.setVanillaLoanPendingRepaid(0);
            loanRepaid(sg, Math.round(repaid), Math.round(remaining));
        }
        if (remaining <= 0) {
            // fully repaid (also below the reaction threshold): the next loan starts a new count
            sg.setVanillaLoanTakings(0);
            sg.setVanillaLoanSurcharge(false);
        }
    }

    /** Trust loss of the bank advisor: loan-trust-per10k per 10,000 €, capped at loan-trust-max. */
    public double loanTrustDelta(long amount) {
        return -Math.min(cfg().getLoanTrustMax(), amount / 10_000.0 * cfg().getLoanTrustPer10k());
    }

    void loanTaken(Savegame sg, long amount, long remaining) {
        sg.setVanillaLoanTakings(sg.getVanillaLoanTakings() + 1);
        boolean surcharge = sg.getVanillaLoanTakings() >= 2;
        sg.setVanillaLoanSurcharge(surcharge);
        if (!active(sg)) {
            return;
        }
        Optional<Character> bank = lookup.bank(sg);
        bank.ifPresent(b -> trust.recordEvent(b, loanTrustDelta(amount), TrustReason.VANILLA_LOAN,
                "Kredit im Spielmenü über " + amount + " €"));
        narration.request(sg, NarrationEventType.VANILLA_LOAN_TAKEN).from(bank.orElse(null))
                .facts(NarrationFacts.builder().put("loanAmount", amount).put("vanillaLoanRemaining", remaining)
                        .put("repeated", surcharge)
                        .put("interestSurchargePercent", surcharge ? percent(cfg().getLoanInterestSurcharge()) : null)
                        .put("surchargeNote", surcharge ? "Weil das nicht das erste Mal ist, kosten neue Kredite unseres Hauses "
                                + "Sie " + german(percent(cfg().getLoanInterestSurcharge()))
                                + " Prozentpunkte mehr Zinsen, bis der andere Kredit getilgt ist." : null).build())
                .category(CommunicationCategory.CREDIT).submit();
        diary.addAuto(sg, "CREDIT", "Geld aus dem Spielmenü geliehen", amount + " € über den Kredit im Finanzmenü des "
                + "Spiels aufgenommen" + (surcharge ? " – die Bank verlangt für neue Kredite einen Zinsaufschlag." : "."),
                null, null);
    }

    void loanRepaid(Savegame sg, long amount, long remaining) {
        if (remaining <= 0) {
            sg.setVanillaLoanTakings(0);
            sg.setVanillaLoanSurcharge(false);
        }
        if (!active(sg)) {
            return;
        }
        Optional<Character> bank = lookup.bank(sg);
        bank.ifPresent(b -> trust.recordEvent(b, cfg().getLoanRepaidTrustDelta(), TrustReason.VANILLA_LOAN_REPAID,
                "Kredit im Spielmenü getilgt: " + amount + " €"));
        narration.request(sg, NarrationEventType.VANILLA_LOAN_REPAID).from(bank.orElse(null))
                .facts(NarrationFacts.builder().put("repaidAmount", amount).put("vanillaLoanRemaining", remaining)
                        .put("fullyRepaid", remaining <= 0).build())
                .category(CommunicationCategory.CREDIT).submit();
        diary.addAuto(sg, "CREDIT", remaining <= 0 ? "Kredit aus dem Spielmenü abbezahlt" : "Kredit aus dem Spielmenü getilgt",
                amount + " € zurückgezahlt" + (remaining <= 0 ? ", der Kredit ist erledigt." : ", offen: " + remaining + " €."),
                null, null);
    }

    /** Surcharge on the interest of new credits (0 without). */
    public double interestSurcharge(Savegame sg) {
        return sg.isVanillaLoanSurcharge() && active(sg) ? cfg().getLoanInterestSurcharge() : 0;
    }

    // ------------------------------------------------------------------------------------------ D2 field menu

    @EventListener
    @Transactional
    public void onFarmlandBypass(FarmlandBypassEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!e.purchase()) {
            soldInMenu(sg, e);
            return;
        }
        Character owner = e.previousOwnerType() == OwnerType.CHARACTER && e.previousOwnerId() != null
                ? characters.findById(e.previousOwnerId()).orElse(null) : null;
        if (owner == null) {
            diary.addAuto(sg, "NEGOTIATION", "Feld " + e.farmlandId() + " im Spielmenü gekauft",
                    "Das freie Feld " + e.farmlandId() + " wurde direkt im Feldmenü des Spiels gekauft.", null, null);
            return;
        }
        boughtOverHead(sg, e, owner);
    }

    void boughtOverHead(Savegame sg, FarmlandBypassEvent e, Character owner) {
        diary.addAuto(sg, "NEGOTIATION", "Feld " + e.farmlandId() + " über den Kopf hinweg gekauft", "Das Feld von "
                + owner.getName() + " wurde direkt im Feldmenü des Spiels gekauft, ohne mit ihm zu verhandeln.", null, null);
        if (!active(sg)) {
            return;
        }
        trust.recordEvent(owner, cfg().getFieldTrustDelta(), TrustReason.FIELD_BYPASS,
                "Feld " + e.farmlandId() + " über den Kopf hinweg gekauft");
        publicActions.record(sg, PublicActionType.FIELD_BYPASS, cfg().getFieldReputationDelta(),
                "Feld " + e.farmlandId() + " von " + owner.getName() + " im Spielmenü gekauft");
        long claim = round10(e.gamePrice() * cfg().getCompensationShare());
        ServiceCase sc = null;
        if (claim > 0 && owner.getStatus() == CharacterStatus.ACTIVE) {
            long now = sg.getCurrentGameTime();
            sc = new ServiceCase();
            sc.setSavegame(sg);
            sc.setKind(CaseKind.COMPENSATION_CLAIM);
            sc.setStatus(CaseStatus.AWAITING_PLAYER);
            sc.setCharacter(owner);
            sc.setFarmlandId(e.farmlandId());
            sc.setOfferAmount(claim);
            sc.setGameTime(now);
            sc.setDeadlineGameTime(now + GameTime.days(cfg().getCompensationDecisionDays()));
            sc.setCreatedAt(Instant.now());
            cases.save(sc);
        }
        narration.request(sg, NarrationEventType.FIELD_BOUGHT_OVER_HEAD).from(owner)
                .facts(NarrationFacts.builder().put("farmlandId", e.farmlandId()).put("gamePrice", e.gamePrice())
                        .put("compensationClaim", sc == null ? null : claim)
                        .put("decisionDays", sc == null ? null : Math.round(cfg().getCompensationDecisionDays()))
                        .put("claimNote", sc == null ? null : "Ich erwarte einen Ausgleich von " + german(claim)
                                + " € – unter „Verträge“ kannst du zahlen oder ablehnen. Du hast "
                                + Math.round(cfg().getCompensationDecisionDays()) + " Tage Zeit.").build())
                .category(CommunicationCategory.NEGOTIATION)
                .related(sc == null ? null : RELATED, sc == null ? null : sc.getId())
                .formLink(sc == null ? null : "/contracts?case=" + sc.getId()).submit();
    }

    void soldInMenu(Savegame sg, FarmlandBypassEvent e) {
        diary.addAuto(sg, "NEGOTIATION", "Feld " + e.farmlandId() + " im Spielmenü verkauft",
                "Das Feld " + e.farmlandId() + " wurde direkt im Feldmenü des Spiels verkauft.", null, null);
        if (!active(sg)) {
            return;
        }
        List<Character> dyn = lookup.activeDynamic(sg);
        Optional<Character> teller = dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER, CharacterRole.NEIGHBOR_FARMER)
                : Optional.of(random.pick(dyn));
        teller.ifPresent(t -> narration.request(sg, NarrationEventType.FIELD_GOSSIP).from(t)
                .facts(NarrationFacts.builder().put("fieldName", String.valueOf(e.farmlandId())).put("topic", "SOLD_IN_MENU")
                        .build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
    }

    ServiceCase openClaim(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.COMPENSATION_CLAIM || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Diese Forderung ist bereits erledigt.");
        }
        return sc;
    }

    /** The player pays the claim (COMPENSATION booking); no further trust loss. */
    @Transactional
    public ServiceCase pay(Savegame sg, Long caseId) {
        ServiceCase sc = openClaim(sg, caseId);
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("PAID");
        sc.setPayoutAmount(sc.getOfferAmount());
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        outbox.money(sg, -sc.getOfferAmount(), MoneyReason.COMPENSATION, "Ausgleich Feld " + sc.getFarmlandId(),
                new Related(RELATED, sc.getId()));
        narration.request(sg, NarrationEventType.COMPENSATION_SETTLED).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", sc.getFarmlandId()).put("compensation", sc.getOfferAmount())
                        .build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", "Ausgleich gezahlt", sc.getOfferAmount() + " € Ausgleich an "
                + sc.getCharacter().getName() + " für Feld " + sc.getFarmlandId() + ".", RELATED, sc.getId());
        return sc;
    }

    /** The player refuses (or ignores) the claim: more trust loss. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long caseId) {
        return refuse(sg, openClaim(sg, caseId), "DECLINED");
    }

    private ServiceCase refuse(Savegame sg, ServiceCase sc, String resolution) {
        sc.setStatus(CaseStatus.DECLINED);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        trust.recordEvent(sc.getCharacter(), cfg().getCompensationDeclineTrustDelta(), TrustReason.COMPENSATION_DECLINED,
                "Ausgleich für Feld " + sc.getFarmlandId() + " verweigert");
        narration.request(sg, NarrationEventType.COMPENSATION_DISPUTE).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", sc.getFarmlandId()).put("compensation", sc.getOfferAmount())
                        .put("ignored", "EXPIRED".equals(resolution)).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", "Ausgleich verweigert", sc.getCharacter().getName() + " bekommt keinen Ausgleich für Feld "
                + sc.getFarmlandId() + ".", RELATED, sc.getId());
        return sc;
    }

    /** Without an answer within the deadline the claim counts as refused. */
    void expireClaims(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.COMPENSATION_CLAIM && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                refuse(sg, sc, "EXPIRED");
            }
        }
    }

    public List<ServiceCase> claims(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.COMPENSATION_CLAIM));
    }

    // ------------------------------------------------------------------------------------------ D3 outside helpers

    /** One hint of the cooperative when a helper runs without employee (A4 workforce block). */
    void outsideHelpers(Savegame sg, FarmFacts f) {
        if (sg.isOutsideHelpersHintSent() || !cfg().isOutsideHelpersHint() || !active(sg) || f.workforce() == null
                || f.workforce().activeJobs() == null
                || f.workforce().activeJobs().stream().noneMatch(j -> j != null && j.employeeId() == null)) {
            return;
        }
        Optional<Character> cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return;
        }
        sg.setOutsideHelpersHintSent(true);
        long outside = f.workforce().activeJobs().stream().filter(j -> j != null && j.employeeId() == null).count();
        narration.request(sg, NarrationEventType.OUTSIDE_HELPERS_HINT).from(cooperative.get())
                .facts(NarrationFacts.builder().put("outsideHelpers", outside).build())
                .category(CommunicationCategory.EMPLOYEE).formLink("/employees").submit();
    }

    /** NumberFormat is not thread-safe - one per call. */
    private static String german(double v) {
        return NumberFormat.getNumberInstance(Locale.GERMANY).format(v);
    }

    private static double percent(double rate) {
        return Math.round(rate * 10000) / 100.0;
    }

    private static long round10(double v) {
        return Math.round(v / 10.0) * 10;
    }
}
