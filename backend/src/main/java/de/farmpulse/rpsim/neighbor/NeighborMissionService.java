package de.farmpulse.rpsim.neighbor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-H5: neighbours ask for help with a real contract of the game on their own field. After the player's
 * yes the backend sends {@code MISSION_CREATE}; the contract appears in the game's contract menu with the neighbour as
 * client (the game's NPC of the farmland). The game pays its reward; the tool evaluates the end via farm_facts.missions:
 * success = trust, thanks and a bonus (OTHER), failure or expiry = disappointed neighbour. Only the evidenced types
 * (plowing, stone picking) are offered; the game's contract limit (farm_facts.missionLimitReached) is checked before an
 * offer. A contract lost by loading an older save is offered again (fallback of the 🟡 point of R3-H5).
 */
@Service
public class NeighborMissionService {

    public static final String RELATED = "NEIGHBOR_MISSION";
    static final String LOST_CHECK = "LOST_CHECK";

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final NeighborService neighbors;
    private final NpcFieldService fields;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public NeighborMissionService(ServiceCaseRepository cases, SavegameRepository savegames,
                                  CharacterRepository characters, NeighborService neighbors, NpcFieldService fields,
                                  OutboxService outbox, OutboxInstructionRepository instructions, TrustScoreService trust,
                                  NarrationRequestService narration, DiaryService diary, FallbackTemplates labels,
                                  RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.characters = characters;
        this.neighbors = neighbors;
        this.fields = fields;
        this.outbox = outbox;
        this.instructions = instructions;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.NeighborMissions cfg() {
        return props.getFormulas().getNeighborMissions();
    }

    // ------------------------------------------------------------------------------------------ candidates

    /** A neighbour field and the contract type it needs. */
    public record Work(Character neighbor, NpcFieldRecord field, String missionType) {
    }

    /**
     * Fields of neighbours that need work: harvested and not plowed yet -> PLOW; many stones -> STONE_PICK. Fields with
     * an open request or a contract of the game are left out; the mod checks the rest (isAvailableForField).
     */
    public List<Work> work(Savegame sg, FarmFacts f, Long neighborId) {
        Set<Integer> busy = new java.util.HashSet<>();
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.NEIGHBOR_MISSION))) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER || sc.getStatus() == CaseStatus.IN_PROGRESS) {
                busy.add(sc.getFarmlandId());
            }
        }
        Set<String> contractFields = new java.util.HashSet<>();
        for (BridgeDtos.Mission m : f.missionList()) {
            if (m.field() != null && !"FINISHED".equals(m.status())) {
                contractFields.add(m.field());
            }
        }
        int stoneHigh = props.getFormulas().getFields().getStoneHighLevel();
        List<Work> out = new ArrayList<>();
        for (NpcFieldRecord r : fields.records(sg)) {
            if (busy.contains(r.getFarmlandId()) || (r.getFieldName() != null && contractFields.contains(r.getFieldName()))) {
                continue;
            }
            Optional<Character> owner = neighbors.ownerOf(sg, r.getFarmlandId());
            if (owner.isEmpty() || (neighborId != null && !owner.get().getId().equals(neighborId))) {
                continue;
            }
            for (String type : cfg().getTypes()) {
                boolean fits = switch (type) {
                    case "PLOW" -> r.getPhase() == FieldPhase.HARVESTED && r.getPlowLevel() != null && r.getPlowLevel() == 0;
                    case "STONE_PICK" -> r.getStoneLevel() != null && r.getStoneLevel() >= stoneHigh;
                    default -> false; // further types after a playtest (manual test plan 11.3)
                };
                if (fits) {
                    out.add(new Work(owner.get(), r, type));
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ offers

    @EventListener
    @Order(82)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.npcFields() == null || Boolean.TRUE.equals(f.missionLimitReached())) {
            return;
        }
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        long thisMonth = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.NEIGHBOR_MISSION)).stream()
                .filter(c -> NeighborTradeService.NEIGHBOR_INITIATIVE.equals(c.getTitle()))
                .filter(c -> gameTime.monthIndex(sg, c.getGameTime()) == month).count();
        if (thisMonth >= cfg().getMaxPerMonth() || !random.chance(cfg().getProbabilityPerMonth())) {
            return;
        }
        List<Work> work = work(sg, f, null);
        if (!work.isEmpty()) {
            offer(sg, random.pick(work), false);
        }
    }

    /** The player asks a neighbour for work (page "Handel"); the neighbour picks a field that needs it. */
    @Transactional
    public ServiceCase askForWork(Savegame sg, Long neighborId) {
        Character n = characters.findById(neighborId).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .filter(NeighborService::isNeighbor).orElseThrow(() -> new NotFoundException("neighbour " + neighborId));
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.npcFields() == null) {
            throw new BusinessRuleException("MISSION_NO_FIELDS", "Der Mod meldet die Felder der Nachbarn nicht – bitte "
                    + "den Mod FS25_RPSim aktualisieren (oder npcFieldExport einschalten).");
        }
        requireBelowLimit(f);
        List<Work> work = work(sg, f, n.getId());
        if (work.isEmpty()) {
            throw new BusinessRuleException("MISSION_NO_WORK", n.getName() + " hat gerade keine Arbeit für dich.");
        }
        return offer(sg, random.pick(work), true);
    }

    private static void requireBelowLimit(FarmFacts f) {
        if (Boolean.TRUE.equals(f.missionLimitReached())) {
            throw new BusinessRuleException("MISSION_LIMIT", "Du hast im Spiel schon die Höchstzahl an Aufträgen – "
                    + "erst einen abschließen.");
        }
    }

    ServiceCase offer(Savegame sg, Work w, boolean playerAsked) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.NEIGHBOR_MISSION);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(w.neighbor());
        sc.setFarmlandId(w.field().getFarmlandId());
        sc.setHectares(w.field().getHectares());
        sc.setReference(w.missionType());
        sc.setOfferAmount(cfg().getSuccessBonus());
        sc.setTitle(playerAsked ? NeighborTradeService.PLAYER_REQUEST : NeighborTradeService.NEIGHBOR_INITIATIVE);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(java.time.Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.NEIGHBOR_MISSION_REQUEST).from(w.neighbor())
                .facts(facts(sc, w.field().getFieldName()).put("playerAsked", playerAsked).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId())
                .formLink(NeighborTradeService.link(sc)).submit();
        return sc;
    }

    private NarrationFacts.Builder facts(ServiceCase sc, String fieldName) {
        return NarrationFacts.builder().put("field", fieldName != null ? fieldName : String.valueOf(sc.getFarmlandId()))
                .put("hectares", sc.getHectares()).put("missionType", sc.getReference()).put("bonus", sc.getOfferAmount())
                .put("answerDays", Math.round(cfg().getAnswerDays()));
    }

    private String fieldName(ServiceCase sc) {
        return fields.records(sc.getSavegame()).stream().filter(r -> r.getFarmlandId() == sc.getFarmlandId())
                .map(NpcFieldRecord::getFieldName).filter(java.util.Objects::nonNull).findFirst()
                .orElse(String.valueOf(sc.getFarmlandId()));
    }

    // ------------------------------------------------------------------------------------------ decisions

    ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.NEIGHBOR_MISSION || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Diese Anfrage ist nicht mehr offen.");
        }
        return sc;
    }

    /** Yes: the contract is created in the game (MISSION_CREATE). */
    @Transactional
    public ServiceCase accept(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        neighbors.latest(sg).ifPresent(NeighborMissionService::requireBelowLimit);
        outbox.missionCreate(sg, sc.getReference(), sc.getFarmlandId(), new Related(RELATED, sc.getId()));
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        return sc;
    }

    @EventListener
    @Order(82)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.NEIGHBOR_MISSION && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
            }
        }
    }

    private static void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ bridge

    /** The contract exists in the game: its uniqueId (ack result) links it to farm_facts.missions. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        boolean create = instructions.findByInstructionId(e.instructionId())
                .map(o -> o.getType() == InstructionType.MISSION_CREATE).orElse(false);
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (!create || sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return;
        }
        Object id = e.result() == null ? null : e.result().get("missionId");
        sc.setExternalId(id == null ? null : id.toString());
        diary.addAuto(sc.getSavegame(), "TRADE", "Auftrag von " + sc.getCharacter().getName(),
                labels.label(sc.getReference()) + " auf Feld " + fieldName(sc) + " – im Auftragsmenü des Spiels.",
                RELATED, sc.getId());
    }

    /** FailedInstructionService: the game refused the contract (NOT_AVAILABLE, older mod ...). */
    @Transactional
    public boolean onInstructionFailed(Long caseId) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.NEIGHBOR_MISSION || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return false;
        }
        close(sc, CaseStatus.EXPIRED, "NOT_AVAILABLE");
        return true;
    }

    /** Loading an older save may lose a created contract: the next export decides (still there = nothing lost). */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (sc.getKind() == CaseKind.NEIGHBOR_MISSION && sc.getExternalId() != null) {
                sc.setResolution(LOST_CHECK);
            }
        }
    }

    /** Evaluates the running contracts against farm_facts.missions (FINISHED + success, or gone = expired / lost). */
    @EventListener
    @Order(7)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.missions() == null) {
            return;
        }
        Map<String, BridgeDtos.Mission> byId = new java.util.HashMap<>();
        f.missions().forEach(m -> byId.put(m.uniqueId(), m));
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (sc.getKind() != CaseKind.NEIGHBOR_MISSION || sc.getExternalId() == null) {
                continue;
            }
            BridgeDtos.Mission m = byId.get(sc.getExternalId());
            if (m != null && "FINISHED".equals(m.status())) {
                finish(sg, sc, Boolean.TRUE.equals(m.success()));
            } else if (m != null) {
                if (LOST_CHECK.equals(sc.getResolution())) {
                    sc.setResolution(null); // still in the reloaded savegame
                }
            } else if (LOST_CHECK.equals(sc.getResolution())) {
                close(sc, CaseStatus.EXPIRED, "LOST");
                offer(sg, new Work(sc.getCharacter(), record(sg, sc), sc.getReference()), false);
            } else {
                finish(sg, sc, false); // the game's deadline passed without the contract being done
            }
        }
    }

    private NpcFieldRecord record(Savegame sg, ServiceCase sc) {
        return fields.records(sg).stream().filter(r -> r.getFarmlandId() == sc.getFarmlandId()).findFirst()
                .orElseGet(() -> {
                    NpcFieldRecord r = new NpcFieldRecord();
                    r.setFarmlandId(sc.getFarmlandId());
                    r.setHectares(sc.getHectares());
                    return r;
                });
    }

    private void finish(Savegame sg, ServiceCase sc, boolean success) {
        Character n = sc.getCharacter();
        String field = fieldName(sc);
        if (success) {
            close(sc, CaseStatus.SETTLED, "COMPLETED");
            sc.setPayoutAmount(cfg().getSuccessBonus());
            trust.recordEvent(n, cfg().getSuccessTrustDelta(), TrustReason.MISSION_COMPLETED, labels.label(sc.getReference()));
            if (cfg().getSuccessBonus() > 0) {
                outbox.money(sg, cfg().getSuccessBonus(), MoneyReason.OTHER, "Bonus von " + n.getName(),
                        new Related(RELATED, sc.getId()));
            }
            narration.request(sg, NarrationEventType.NEIGHBOR_MISSION_THANKS).from(n).facts(facts(sc, field).build())
                    .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
            diary.addAuto(sg, "TRADE", "Auftrag für " + n.getName() + " erledigt", labels.label(sc.getReference())
                    + " auf Feld " + field + ".", RELATED, sc.getId());
        } else {
            close(sc, CaseStatus.EXPIRED, "FAILED");
            trust.recordEvent(n, cfg().getFailureTrustDelta(), TrustReason.MISSION_FAILED, labels.label(sc.getReference()));
            narration.request(sg, NarrationEventType.NEIGHBOR_MISSION_DISAPPOINTED).from(n).facts(facts(sc, field).build())
                    .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
        }
    }

    public List<ServiceCase> cases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.NEIGHBOR_MISSION));
    }
}
