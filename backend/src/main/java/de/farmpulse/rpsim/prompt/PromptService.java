package de.farmpulse.rpsim.prompt;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.GamePrompt;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PlayerResponse;
import de.farmpulse.rpsim.domain.PromptKind;
import de.farmpulse.rpsim.domain.PromptStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.CreditApplicationRepository;
import de.farmpulse.rpsim.repository.GamePromptRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PlayerResponseRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Roadmap V2 R2-F: decisions directly in the game.
 * <ul>
 *   <li>Every bridge cycle (before instructions.json is written) the open decisions of the enabled occasions are
 *   compared with the questions already asked: a new one becomes a PROMPT instruction, a question whose decision was
 *   made in the browser or whose occasion ended is withdrawn (owner decision), an old one expires.</li>
 *   <li>Answers from export/player_responses.json are stored once per responseId (idempotent) and carried out after
 *   the cycle, each in its own transaction, with the same service methods as the buttons in the browser. A refused
 *   action (e.g. not enough money) comes back to the game as a notification.</li>
 *   <li>After a reload without saving an open question the mod lost is sent again.</li>
 * </ul>
 * The numbers of every question come from the backend; the mod shows the game's yes / no buttons and the meaning of
 * the buttons in the text (owner decision).
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    public static final String RELATED = "PROMPT";
    static final String DONE = "DONE";
    static final String NOTHING = "NOTHING";
    static final String LEASE_RENEWAL = "LEASE_RENEWAL";
    static final int MAX_TEXT = 500;

    /** One open decision that can be asked in the game. */
    record Candidate(PromptKind kind, String key, long targetId, String title, String text, String yesLabel,
                     String noLabel, long expiresGameTime) {
    }

    private final SavegameRepository savegames;
    private final GamePromptRepository prompts;
    private final PlayerResponseRepository responses;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final CommunicationRepository communications;
    private final ContractRepository contracts;
    private final ServiceCaseRepository cases;
    private final CreditApplicationRepository applications;
    private final CallService calls;
    private final ContractActions actions;
    private final CreditApplicationService credit;
    private final FallbackTemplates labels;
    private final RpsimProperties props;
    private final TransactionTemplate tx;

    public PromptService(SavegameRepository savegames, GamePromptRepository prompts, PlayerResponseRepository responses,
                         OutboxService outbox, OutboxInstructionRepository instructions,
                         CommunicationRepository communications, ContractRepository contracts,
                         ServiceCaseRepository cases, CreditApplicationRepository applications, CallService calls,
                         ContractActions actions, CreditApplicationService credit, FallbackTemplates labels,
                         RpsimProperties props, PlatformTransactionManager transactions) {
        this.savegames = savegames;
        this.prompts = prompts;
        this.responses = responses;
        this.outbox = outbox;
        this.instructions = instructions;
        this.communications = communications;
        this.contracts = contracts;
        this.cases = cases;
        this.applications = applications;
        this.calls = calls;
        this.actions = actions;
        this.credit = credit;
        this.labels = labels;
        this.props = props;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------------------------------ settings

    /** Occasions asked in the game for this savegame (default: rpsim.bridge.prompt-default-kinds, i.e. only calls). */
    public Set<PromptKind> enabledKinds(Savegame sg) {
        String stored = sg.getIngamePromptKinds();
        List<String> names = stored == null ? props.getBridge().getPromptDefaultKinds()
                : Arrays.stream(stored.split(",")).filter(s -> !s.isBlank()).toList();
        Set<PromptKind> kinds = EnumSet.noneOf(PromptKind.class);
        for (String n : names) {
            try {
                kinds.add(PromptKind.valueOf(n.strip()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown in-game question kind '{}' ignored", n);
            }
        }
        return kinds;
    }

    @Transactional
    public Set<PromptKind> setEnabledKinds(Savegame sg, Set<PromptKind> kinds) {
        sg.setIngamePromptKinds(kinds.stream().sorted().map(Enum::name).collect(Collectors.joining(",")));
        return enabledKinds(sg);
    }

    public boolean globallyEnabled() {
        return props.getBridge().isIngamePrompts();
    }

    // ------------------------------------------------------------------------------------------ asking

    @EventListener
    public void onInstructionsWriting(BridgeEvents.InstructionsWriting e) {
        savegames.findById(e.savegameId()).ifPresent(this::sync);
    }

    /** Asks new decisions, withdraws decided ones and expires old ones. Runs inside the bridge cycle. */
    @Transactional
    public void sync(Savegame sg) {
        if (sg.getLinkedAt() == null) {
            return;
        }
        long now = sg.getCurrentGameTime();
        Set<PromptKind> enabled = globallyEnabled() ? enabledKinds(sg) : EnumSet.noneOf(PromptKind.class);
        Map<String, Candidate> open = new HashMap<>();
        for (Candidate c : candidates(sg)) {
            if (enabled.contains(c.kind())) {
                open.put(c.key(), c);
            }
        }
        for (GamePrompt p : prompts.findBySavegameAndStatusOrderByIdAsc(sg, PromptStatus.OPEN)) {
            if (!open.containsKey(p.getPromptKey())) {
                p.setStatus(PromptStatus.WITHDRAWN); // decided in the browser, occasion ended or switched off
            } else if (p.getExpiresGameTime() < now) {
                p.setStatus(PromptStatus.EXPIRED);
            }
        }
        for (Candidate c : open.values()) {
            if (c.expiresGameTime() <= now || prompts.findBySavegameAndPromptKey(sg, c.key()).isPresent()) {
                continue;
            }
            ask(sg, c);
        }
    }

    GamePrompt ask(Savegame sg, Candidate c) {
        GamePrompt p = new GamePrompt();
        p.setSavegame(sg);
        p.setPromptId("prm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        p.setPromptKey(c.key());
        p.setKind(c.kind());
        p.setTargetId(c.targetId());
        p.setTitle(shorten(c.title(), 120));
        p.setText(shorten(c.text(), MAX_TEXT));
        p.setCreatedGameTime(sg.getCurrentGameTime());
        p.setExpiresGameTime(c.expiresGameTime());
        prompts.save(p);
        OutboxInstruction ins = outbox.prompt(sg, p.getPromptId(), p.getTitle(), p.getText(), c.yesLabel(), c.noLabel(),
                c.expiresGameTime(), new Related(RELATED, p.getId()));
        p.setInstructionId(ins.getInstructionId());
        return p;
    }

    /** Every decision that is open right now, independent of the settings (switched off = withdrawn). */
    List<Candidate> candidates(Savegame sg) {
        long now = sg.getCurrentGameTime();
        long maxAge = now + GameTime.hours(props.getBridge().getPromptMaxAgeHours());
        List<Candidate> list = new ArrayList<>();
        for (Communication c : communications.findBySavegameAndChannelAndCallStatus(sg, Channel.CALL, CallStatus.RINGING)) {
            String name = name(c.getCharacter() == null ? null : c.getCharacter().getName());
            String subject = c.getSubject() == null || c.getSubject().isBlank() ? "" : " (" + c.getSubject() + ")";
            list.add(new Candidate(PromptKind.CALL, "CALL:" + c.getId(), c.getId(), "Anruf von " + name,
                    name + " ruft an" + subject + ". Nimmst du an, ist das Gespräch im Browser bereit.",
                    "Annehmen", "Ablehnen", c.getRingDeadlineGameTime() != null ? c.getRingDeadlineGameTime() : maxAge));
        }
        for (Contract c : contracts.findBySavegameOrderByIdDesc(sg)) {
            String from = name(c.getCharacter() == null ? null : c.getCharacter().getName());
            long expires = c.getOfferExpiresAtGameTime() != null ? c.getOfferExpiresAtGameTime() : maxAge;
            if (c.getStatus() == ContractStatus.OFFERED) {
                String text = switch (c.getKind()) {
                    case INSURANCE -> from + " bietet die Versicherung " + labels.label(c.getLevel()) + " für "
                            + money(c.getMonthlyAmount()) + " im Monat an: "
                            + Math.round((c.getCoverageRate() == null ? 0 : c.getCoverageRate()) * 100)
                            + " % Erstattung, " + money(c.getDeductible()) + " Selbstbehalt.";
                    case LEASE -> from + " verpachtet dir Feld " + c.getFarmlandId() + " für " + money(c.getMonthlyAmount())
                            + " im Monat, " + c.getTermMonths() + " Monate.";
                    case MAINTENANCE -> from + " bietet einen Wartungsvertrag für " + money(c.getMonthlyAmount())
                            + " im Monat an.";
                    case TAX_ADVISOR -> from + " übernimmt deine Steuern für " + money(c.getMonthlyAmount())
                            + " im Monat und senkt die Steuer um "
                            + Math.round(props.getFormulas().getTax().getAdvisorTaxReduction() * 100) + " %.";
                    case WINTER_SERVICE -> from + " bietet den Winterdienst für " + money(c.getMonthlyAmount())
                            + " je Wintermonat plus " + money(props.getFormulas().getWinterService().getFeePerSnowDay())
                            + " je Einsatztag an."; // R31-A4
                    default -> null;
                };
                if (text != null) {
                    PromptKind kind = c.getKind() == ContractKind.TAX_ADVISOR ? PromptKind.TAX_ADVISOR : PromptKind.CONTRACT_OFFER;
                    list.add(new Candidate(kind, "CONTRACT:" + c.getId(), c.getId(), "Vertragsangebot", text,
                            "Annehmen", "Ablehnen", expires));
                }
            } else if (c.getStatus() == ContractStatus.ACTIVE && c.getKind() == ContractKind.LEASE
                    && c.getRenewalAmount() != null && c.getEndsAtGameTime() != null) {
                list.add(new Candidate(PromptKind.CONTRACT_OFFER, LEASE_RENEWAL + ":" + c.getId() + ":" + c.getEndsAtGameTime(),
                        c.getId(), "Pacht verlängern", from + " verlängert die Pacht von Feld " + c.getFarmlandId()
                        + " um " + c.getTermMonths() + " Monate für " + money(c.getRenewalAmount()) + " im Monat.",
                        "Verlängern", "Später im Browser", c.getEndsAtGameTime()));
            }
        }
        for (ServiceCase s : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            String from = name(s.getCharacter() == null ? null : s.getCharacter().getName());
            long deadline = s.getDeadlineGameTime() != null ? s.getDeadlineGameTime() : maxAge;
            switch (s.getKind()) {
                case WILDLIFE_DAMAGE -> {
                    if (s.getOfferAmount() != null) {
                        list.add(new Candidate(PromptKind.WILDLIFE_OFFER, "WILDLIFE:" + s.getId() + ":" + s.getRoundsUsed(),
                                s.getId(), "Wildschaden", from + " bietet " + money(s.getOfferAmount())
                                + " Ersatz für den Wildschaden auf Feld " + s.getFarmlandId() + ".",
                                "Annehmen", "Im Browser verhandeln", deadline));
                    }
                }
                case COMPENSATION_CLAIM -> list.add(new Candidate(PromptKind.COMPENSATION_CLAIM, "CASE:" + s.getId(),
                        s.getId(), "Ausgleichsforderung", from + " verlangt " + money(s.getOfferAmount())
                        + " Ausgleich für Feld " + s.getFarmlandId() + ".", "Zahlen", "Ablehnen", deadline));
                case TAX_BILL -> {
                    long total = (s.getOfferAmount() == null ? 0 : s.getOfferAmount())
                            + (s.getCostAmount() == null ? 0 : s.getCostAmount());
                    // a late fee changes the amount: the question is asked again with the new total
                    list.add(new Candidate(PromptKind.TAX_BILL, "TAX_BILL:" + s.getId() + ":" + s.getRoundsUsed(),
                            s.getId(), "Finanzamt", (s.getTitle() == null ? "Steuerbescheid" : s.getTitle()) + ": "
                            + money(total) + " zahlen.", "Zahlen", "Später", Math.max(deadline, maxAge)));
                }
                case INVITATION -> list.add(new Candidate(PromptKind.INVITATION, "CASE:" + s.getId(), s.getId(),
                        "Einladung", from + " lädt dich zum " + labels.label(s.getReference()) + " ein.",
                        "Zusagen", "Absagen", deadline));
                // Roadmap V3 R3-H3 / R3-H4 / R3-H5 (owner decision: own occasions, default off)
                case GOODS_OFFER -> list.add(new Candidate(PromptKind.NEIGHBOR_TRADE, "CASE:" + s.getId(), s.getId(),
                        "Ware vom Nachbarn", from + " verkauft dir " + s.getQuantity() + " l "
                        + labels.label(s.getReference()) + " für " + money(s.getOfferAmount()) + ".",
                        "Kaufen", "Ablehnen", deadline));
                case GOODS_REQUEST -> list.add(new Candidate(PromptKind.NEIGHBOR_TRADE, "CASE:" + s.getId(), s.getId(),
                        "Anfrage vom Nachbarn", from + " möchte " + s.getQuantity() + " l "
                        + labels.label(s.getReference()) + " aus deinem Silo für " + money(s.getOfferAmount()) + ".",
                        "Verkaufen", "Ablehnen", deadline));
                case NEIGHBOR_MISSION -> list.add(new Candidate(PromptKind.NEIGHBOR_MISSION, "CASE:" + s.getId(),
                        s.getId(), "Arbeit beim Nachbarn", from + " bittet dich: " + labels.label(s.getReference())
                        + " auf Feld " + s.getFarmlandId() + " (Bonus " + money(s.getOfferAmount()) + ").",
                        "Zusagen", "Absagen", deadline));
                // Roadmap V3 R3-M3 (owner decision: own occasion, default off)
                case FARM_SHOP_ORDER -> list.add(new Candidate(PromptKind.FARM_SHOP, "CASE:" + s.getId(), s.getId(),
                        "Hofladen", from + " bestellt " + s.getQuantity() + " l " + labels.label(s.getReference())
                        + " für " + money(s.getOfferAmount()) + ".", "Liefern", "Ablehnen", deadline));
                // Roadmap V3.1 R31-D5: crop damage claim, paid or refused like the compensation claim of R2-D2
                case CROP_DAMAGE_CLAIM -> list.add(new Candidate(PromptKind.COMPENSATION_CLAIM, "CASE:" + s.getId(),
                        s.getId(), "Flurschaden", from + " verlangt " + money(s.getOfferAmount())
                        + " Entschädigung für Fahrspuren auf Feld " + s.getFarmlandId() + ".", "Zahlen", "Ablehnen", deadline));
                // Roadmap V3.1 R31-D3 / R31-D7 (owner decisions: own occasions, default off)
                case STAMMTISCH_INVITATION -> list.add(new Candidate(PromptKind.STAMMTISCH, "CASE:" + s.getId(), s.getId(),
                        "Stammtisch", from + " lädt dich zum Stammtisch in die Dorfkneipe ein.", "Hingehen", "Absagen",
                        deadline));
                case COOP_ASSEMBLY -> list.add(new Candidate(PromptKind.COOP_ASSEMBLY, "CASE:" + s.getId(), s.getId(),
                        "Generalversammlung", "Abstimmung der Genossenschaft: " + labels.label(s.getReference()) + "?",
                        "Ja", "Nein", deadline));
                default -> {
                    // other cases are decided in the browser only
                }
            }
        }
        for (CreditApplication a : applications.findBySavegameAndStatus(sg, CreditApplicationStatus.DECIDED)) {
            if (a.getDecision() == CreditDecision.COUNTER_OFFER && a.getOfferedAmount() != null) {
                double rate = a.getOfferedInterestRate() == null ? 0 : Math.round(a.getOfferedInterestRate() * 10000) / 100.0;
                list.add(new Candidate(PromptKind.CREDIT_COUNTER, "CREDIT:" + a.getId(), a.getId(), "Gegenangebot der Bank",
                        "Statt " + money(a.getAmount()) + " bietet die Bank " + money(a.getOfferedAmount()) + " über "
                        + a.getOfferedTermMonths() + " Monate zu " + NumberFormat.getNumberInstance(Locale.GERMANY)
                        .format(rate) + " % Zins an.", "Annehmen", "Ablehnen", maxAge));
            }
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------ answers

    /** Stores new answers (idempotent by responseId); they are carried out after the cycle ({@link #processAnswers}). */
    @EventListener
    @Transactional
    public void onResponses(BridgeEvents.PlayerResponsesRead e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (BridgeDtos.PlayerAnswer a : e.responses()) {
            if (responses.existsBySavegameAndResponseId(sg, a.responseId())) {
                continue;
            }
            PlayerResponse r = new PlayerResponse();
            r.setSavegame(sg);
            r.setResponseId(a.responseId());
            r.setPromptId(a.promptId());
            r.setAnswer(a.answer());
            r.setGameTime(a.gameTime());
            r.setReceivedAt(Instant.now());
            responses.save(r);
            Optional<GamePrompt> p = prompts.findByPromptId(a.promptId())
                    .filter(x -> x.getSavegame().getId().equals(sg.getId()));
            if (p.isEmpty() || p.get().getStatus() != PromptStatus.OPEN) {
                // decided in the browser meanwhile (or unknown): the answer is ignored
                log.info("Answer {} to question {} ignored: {}", a.responseId(), a.promptId(),
                        p.map(x -> x.getStatus().name()).orElse("unknown question"));
                continue;
            }
            p.get().setStatus(PromptStatus.ANSWERED);
            p.get().setAnswer(a.answer());
            p.get().setAnsweredGameTime(a.gameTime());
        }
    }

    /**
     * Carries out the answers read in the last cycle, each in its own transaction: a refused action (business rule)
     * rolls back only itself and comes back to the game as a notification. Called after the bridge cycle.
     */
    public int processAnswers() {
        List<Long> ids = tx.execute(s -> prompts.findByStatusAndResultIsNullOrderByIdAsc(PromptStatus.ANSWERED).stream()
                .map(GamePrompt::getId).toList());
        int n = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            String result;
            try {
                result = tx.execute(s -> carryOut(prompts.findById(id).orElseThrow()));
            } catch (BusinessRuleException | NotFoundException ex) {
                result = ex.getMessage();
            }
            String finalResult = result == null ? DONE : result;
            tx.executeWithoutResult(s -> finish(prompts.findById(id).orElseThrow(), finalResult));
            n++;
        }
        return n;
    }

    /** The same service methods as the buttons in the browser. Returns DONE or NOTHING (a "no" that decides nothing). */
    String carryOut(GamePrompt p) {
        Savegame sg = p.getSavegame();
        boolean yes = "YES".equals(p.getAnswer());
        long id = p.getTargetId();
        switch (p.getKind()) {
            case CALL -> {
                if (yes) {
                    calls.accept(sg, id);
                } else {
                    calls.decline(sg, id);
                }
            }
            case CONTRACT_OFFER, TAX_ADVISOR -> {
                if (p.getPromptKey().startsWith(LEASE_RENEWAL + ":")) {
                    if (!yes) {
                        return NOTHING; // renewal stays open in the browser until the end of the term
                    }
                    actions.renew(sg, id);
                } else if (yes) {
                    actions.accept(sg, id);
                } else {
                    actions.decline(sg, id);
                }
            }
            case WILDLIFE_OFFER, TAX_BILL -> {
                if (!yes) {
                    return NOTHING; // negotiating / paying later stays in the browser
                }
                actions.acceptCase(sg, id);
            }
            case COMPENSATION_CLAIM, INVITATION, NEIGHBOR_TRADE, NEIGHBOR_MISSION, FARM_SHOP, STAMMTISCH, COOP_ASSEMBLY -> {
                if (yes) {
                    actions.acceptCase(sg, id);
                } else {
                    actions.declineCase(sg, id);
                }
            }
            case CREDIT_COUNTER -> {
                if (yes) {
                    credit.acceptCounterOffer(sg, id);
                } else {
                    credit.declineCounterOffer(sg, id);
                }
            }
        }
        return DONE;
    }

    void finish(GamePrompt p, String result) {
        p.setResult(shorten(result, 255));
        Savegame sg = p.getSavegame();
        long expires = sg.getCurrentGameTime() + GameTime.hours(props.getBridge().getNotificationMaxAgeHours());
        if (!DONE.equals(result) && !NOTHING.equals(result)) {
            log.info("Answer to question {} not carried out: {}", p.getPromptId(), result);
            outbox.notification(sg, shorten("FarmPulse: " + result, 120), "CRITICAL", expires, new Related(RELATED, p.getId()));
        } else if (p.getKind() == PromptKind.CALL && "YES".equals(p.getAnswer())) {
            outbox.notification(sg, "FarmPulse: Das Gespräch ist im Browser bereit.", "OK", expires,
                    new Related(RELATED, p.getId()));
        }
    }

    // ------------------------------------------------------------------------------------------ bridge events

    /** R2-F1: an open question whose PROMPT the reloaded savegame does not know (acknowledged after that point) is sent again. */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (GamePrompt p : prompts.findBySavegameAndStatusOrderByIdAsc(sg, PromptStatus.OPEN)) {
            if (p.getInstructionId() == null || p.getExpiresGameTime() < e.rewoundToGameTime()) {
                continue;
            }
            instructions.findByInstructionId(p.getInstructionId())
                    .filter(o -> o.getStatus() == InstructionStatus.APPLIED && o.getAckedAtGameTime() != null
                            && o.getAckedAtGameTime() > e.rewoundToGameTime())
                    .ifPresent(o -> {
                        o.setStatus(InstructionStatus.PENDING);
                        o.setAckedAtGameTime(null);
                        o.setAckMessage(null);
                    });
        }
    }

    /** An older mod refuses PROMPT (NOT_SUPPORTED): the decision simply stays in the browser, no notice. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if ("APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        instructions.findByInstructionId(e.instructionId()).filter(o -> o.getType() == InstructionType.PROMPT)
                .flatMap(o -> prompts.findById(e.relatedId()))
                .filter(p -> p.getStatus() == PromptStatus.OPEN)
                .ifPresent(p -> p.setStatus(PromptStatus.NOT_SHOWN));
    }

    // ------------------------------------------------------------------------------------------ helpers

    public List<GamePrompt> recent(Savegame sg, int max) {
        return prompts.findBySavegameOrderByIdDesc(sg).stream().limit(max).toList();
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "FarmPulse" : n;
    }

    static String money(Long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount == null ? 0 : amount) + " €";
    }

    static String money(long amount) {
        return money(Long.valueOf(amount));
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
