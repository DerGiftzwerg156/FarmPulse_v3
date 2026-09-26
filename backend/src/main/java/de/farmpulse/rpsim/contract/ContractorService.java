package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.Mission;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-22 contractor: vanilla contracts of the game are "referred" by a village character. The mod exports the
 * contracts (farm_facts.missions); newly available ones are referred by mail with some probability, the player takes
 * them in the game's contracts menu as usual (nothing is started by the tool - compatible with FS25_BetterContracts,
 * which only changes the game's own contract data). When a referred contract finishes for the player farm, trust with
 * the contractor and with the client (FS25 NPC, if it is a village character, T-21) changes.
 */
@Service
public class ContractorService {

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final FactsService facts;
    private final ServiceRoleService roles;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public ContractorService(ServiceCaseRepository cases, SavegameRepository savegames, CharacterRepository characters,
                             FactsService facts, ServiceRoleService roles, TrustScoreService trust,
                             NarrationRequestService narration, DiaryService diary, RandomSource random,
                             RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.characters = characters;
        this.facts = facts;
        this.roles = roles;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Contractor cfg() {
        return props.getFormulas().getContractor();
    }

    @EventListener
    @Order(80)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        facts.latest(sg).ifPresent(f -> sync(sg, f));
    }

    /** Follows the referred contracts and refers new ones. */
    @Transactional
    public void sync(Savegame sg, FarmFacts f) {
        Map<String, Mission> byId = f.missionList().stream().filter(m -> m.uniqueId() != null)
                .collect(Collectors.toMap(Mission::uniqueId, m -> m, (a, b) -> a));
        List<ServiceCase> referrals = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.MISSION_REFERRAL));
        Set<String> known = referrals.stream().map(ServiceCase::getReference).collect(Collectors.toSet());
        for (ServiceCase sc : referrals) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER || sc.getStatus() == CaseStatus.IN_PROGRESS) {
                follow(sg, sc, byId.get(sc.getReference()));
            }
        }
        long now = sg.getCurrentGameTime();
        long thisMonth = referrals.stream()
                .filter(sc -> gameTime.monthIndex(sg, sc.getGameTime()) == gameTime.monthIndex(sg, now)).count();
        for (Mission m : f.missionList()) {
            if (thisMonth >= cfg().getMaxReferralsPerMonth()) {
                break;
            }
            if ("AVAILABLE".equals(m.status()) && m.uniqueId() != null && !known.contains(m.uniqueId())) {
                known.add(m.uniqueId()); // decided once per contract
                if (random.chance(cfg().getReferralProbability())) {
                    refer(sg, m);
                    thisMonth++;
                } else {
                    skip(sg, m);
                }
            }
        }
    }

    private ServiceCase newCase(Savegame sg, Character contractor, Mission m) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.MISSION_REFERRAL);
        sc.setCharacter(contractor);
        sc.setReference(m.uniqueId());
        sc.setTitle(m.title());
        sc.setFarmlandId(fieldNumber(m.field()));
        sc.setOfferAmount(m.reward() == null ? null : Math.round(m.reward()));
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setCreatedAt(Instant.now());
        return sc;
    }

    /** A contract that is not referred is remembered (closed) so it is never offered later. */
    private void skip(Savegame sg, Mission m) {
        ServiceCase sc = newCase(sg, null, m);
        sc.setStatus(CaseStatus.EXPIRED);
        sc.setResolution("NOT_REFERRED");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        cases.save(sc);
    }

    ServiceCase refer(Savegame sg, Mission m) {
        Character contractor = roles.ensure(sg, CharacterRole.CONTRACTOR);
        ServiceCase sc = newCase(sg, contractor, m);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        cases.save(sc);
        narration.request(sg, NarrationEventType.MISSION_REFERRAL).from(contractor)
                .facts(facts(m).build())
                .category(CommunicationCategory.CONTRACT).related(InsuranceService.RELATED, sc.getId()).submit();
        return sc;
    }

    private static NarrationFacts.Builder facts(Mission m) {
        return NarrationFacts.builder().put("title", m.title()).put("field", m.field()).put("client", m.npcTitle())
                .put("reward", m.reward() == null ? null : Math.round(m.reward()));
    }

    private void follow(Savegame sg, ServiceCase sc, Mission m) {
        long now = sg.getCurrentGameTime();
        if (m == null) {
            // taken by nobody / expired in the game (or the player's contract was dismissed after payout)
            sc.setResolution(sc.getStatus() == CaseStatus.IN_PROGRESS ? "GONE" : "NOT_TAKEN");
            sc.setStatus(CaseStatus.EXPIRED);
            sc.setClosedAtGameTime(now);
            return;
        }
        if ("RUNNING".equals(m.status())) {
            sc.setStatus(CaseStatus.IN_PROGRESS);
        } else if ("FINISHED".equals(m.status())) {
            finish(sg, sc, m, Boolean.TRUE.equals(m.success()));
        }
    }

    private void finish(Savegame sg, ServiceCase sc, Mission m, boolean success) {
        sc.setStatus(success ? CaseStatus.SETTLED : CaseStatus.EXPIRED);
        sc.setResolution(success ? "COMPLETED" : "FAILED");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        Character contractor = sc.getCharacter();
        trust.recordEvent(contractor, success ? cfg().getCompletedTrustDelta() : cfg().getFailedTrustDelta(),
                success ? TrustReason.MISSION_COMPLETED : TrustReason.MISSION_FAILED, m.title());
        if (success && m.npcIndex() != null) {
            characters.findFirstBySavegameAndFs25NpcIndex(sg, m.npcIndex())
                    .filter(c -> c.getStatus() == CharacterStatus.ACTIVE)
                    .ifPresent(client -> trust.recordEvent(client, cfg().getClientTrustDelta(), TrustReason.MISSION_COMPLETED,
                            m.title()));
        }
        narration.request(sg, success ? NarrationEventType.MISSION_THANKS : NarrationEventType.MISSION_FAILED).from(contractor)
                .facts(facts(m).build())
                .category(CommunicationCategory.CONTRACT).related(InsuranceService.RELATED, sc.getId()).submit();
        if (success) {
            diary.addAuto(sg, "CONTRACT", "Vermittelter Auftrag erledigt", m.title() + " auf Feld " + m.field() + ".",
                    InsuranceService.RELATED, sc.getId());
        }
    }

    /** Field number of the game ("12"), null if not numeric. */
    static Integer fieldNumber(String field) {
        if (field == null) {
            return null;
        }
        try {
            return Integer.valueOf(field.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
