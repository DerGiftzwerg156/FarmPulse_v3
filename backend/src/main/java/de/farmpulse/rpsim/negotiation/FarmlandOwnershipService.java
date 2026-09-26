package de.farmpulse.rpsim.negotiation;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.RewindService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link FarmlandOwnership} consistent with the facts export and silently reconciles vanilla purchases/sales
 * made in the FS25 field menu outside the tool (no lock, just follow-up - like the vanilla loan).
 */
@Service
public class FarmlandOwnershipService {

    private final FarmlandOwnershipRepository repo;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxInstructionRepository outbox;
    private final CharacterLookup lookup;
    private final RandomSource random;
    private final RpsimProperties props;
    private final JsonMapper json;
    private final RewindService rewinds;

    public FarmlandOwnershipService(FarmlandOwnershipRepository repo, SavegameRepository savegames, FactsService facts,
                                    OutboxInstructionRepository outbox, CharacterLookup lookup, RandomSource random,
                                    RpsimProperties props, JsonMapper json, RewindService rewinds) {
        this.repo = repo;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.lookup = lookup;
        this.random = random;
        this.props = props;
        this.json = json;
        this.rewinds = rewinds;
    }

    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        reconcile(savegames.findById(e.savegameId()).orElseThrow());
    }

    @EventListener
    @Transactional
    public void onMarketContext(BridgeEvents.MarketContextUpdated e) {
        reconcile(savegames.findById(e.savegameId()).orElseThrow());
    }

    /** Farmland ids with a FARMLAND_TRANSFER that the mod has not applied yet (skip reconciliation for them). */
    Set<Integer> pendingTransfers(Savegame sg) {
        Set<Integer> ids = new HashSet<>();
        outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING).stream()
                .filter(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)
                .forEach(o -> ids.add(json.readTree(o.getPayloadJson()).path("farmlandId").asInt()));
        return ids;
    }

    @Transactional
    public void reconcile(Savegame sg) {
        Optional<MarketContext> ctx = facts.marketContext(sg);
        Optional<FarmFacts> latest = facts.latest(sg);
        Set<Integer> playerOwned = new HashSet<>();
        latest.ifPresent(f -> f.assets().farmland().forEach(fl -> playerOwned.add(fl.farmlandId())));
        Set<Integer> pending = pendingTransfers(sg);
        // T-02: after a reload the game shows the old owner until the lost transfer is re-sent / decided
        pending.addAll(rewinds.farmlandsOnHold(sg));
        List<Character> npcs = lookup.activeDynamic(sg);
        if (ctx.isPresent()) {
            for (BridgeDtos.MapFarmland mf : ctx.get().farmlands()) {
                FarmlandOwnership o = repo.findBySavegameAndFarmlandId(sg, mf.farmlandId()).orElse(null);
                if (o == null) {
                    o = new FarmlandOwnership();
                    o.setSavegame(sg);
                    o.setFarmlandId(mf.farmlandId());
                    if (playerOwned.contains(mf.farmlandId())) {
                        o.setOwnerType(OwnerType.PLAYER);
                    } else if (!npcs.isEmpty() && random.chance(props.getFormulas().getNegotiation().getNpcOwnedShare())) {
                        o.setOwnerType(OwnerType.CHARACTER);
                        o.setOwnerCharacter(random.pick(npcs));
                    } else {
                        o.setOwnerType(OwnerType.UNCLAIMED);
                    }
                }
                o.setHectares(mf.hectares() == null ? 0 : mf.hectares());
                o.setReferencePrice(mf.price() == null ? 0 : Math.round(mf.price()));
                o.setUpdatedAtGameTime(sg.getCurrentGameTime());
                repo.save(o);
            }
        }
        if (latest.isEmpty()) {
            return;
        }
        for (FarmlandOwnership o : repo.findBySavegameOrderByFarmlandIdAsc(sg)) {
            if (pending.contains(o.getFarmlandId())) {
                continue;
            }
            boolean ownedInGame = playerOwned.contains(o.getFarmlandId());
            if (ownedInGame && o.getOwnerType() != OwnerType.PLAYER) {
                // vanilla purchase in the field menu -> follow up silently
                o.setOwnerType(OwnerType.PLAYER);
                o.setOwnerCharacter(null);
            } else if (!ownedInGame && o.getOwnerType() == OwnerType.PLAYER) {
                // vanilla sale -> follow up silently
                o.setOwnerType(OwnerType.UNCLAIMED);
                o.setOwnerCharacter(null);
            }
        }
    }

    @Transactional
    public void setOwner(Savegame sg, int farmlandId, OwnerType type, Character character) {
        FarmlandOwnership o = repo.findBySavegameAndFarmlandId(sg, farmlandId).orElseThrow();
        o.setOwnerType(type);
        o.setOwnerCharacter(type == OwnerType.CHARACTER ? character : null);
        o.setUpdatedAtGameTime(sg.getCurrentGameTime());
    }

    public List<FarmlandOwnership> list(Savegame sg) {
        return repo.findBySavegameOrderByFarmlandIdAsc(sg);
    }

    public Optional<FarmlandOwnership> get(Savegame sg, int farmlandId) {
        return repo.findBySavegameAndFarmlandId(sg, farmlandId);
    }

    public List<FarmlandOwnership> ownedBy(Character c) {
        return repo.findByOwnerCharacter(c);
    }
}
