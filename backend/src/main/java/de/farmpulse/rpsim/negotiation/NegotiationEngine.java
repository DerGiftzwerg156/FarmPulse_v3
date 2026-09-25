package de.farmpulse.rpsim.negotiation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.Initiator;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationDirection;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationOffer;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OfferParty;
import de.farmpulse.rpsim.domain.OfferResult;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.NegotiationOfferRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic negotiation engine for tradeable goods (functional concept "Verhandlungssystem"). V1 knows exactly one
 * asset type ({@link AssetType#FARMLAND}); the engine itself is deliberately not farmland specific. Prices come
 * exclusively from {@link NegotiationFormula}; the AI only narrates results. No leasing (V1 scope exclusion).
 */
@Service
public class NegotiationEngine {

    public static final String RELATED = "NEGOTIATION";

    private final NegotiationRepository negotiations;
    private final NegotiationOfferRepository offers;
    private final FarmlandOwnershipService ownership;
    private final SavegameRepository savegames;
    private final OutboxService outbox;
    private final LiquidityService liquidity;
    private final NarrationRequestService narration;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;

    public NegotiationEngine(NegotiationRepository negotiations, NegotiationOfferRepository offers,
                             FarmlandOwnershipService ownership, SavegameRepository savegames, OutboxService outbox,
                             LiquidityService liquidity, NarrationRequestService narration, CharacterLookup lookup,
                             TrustScoreService trust, DiaryService diary, RandomSource random, RpsimProperties props) {
        this.negotiations = negotiations;
        this.offers = offers;
        this.ownership = ownership;
        this.savegames = savegames;
        this.outbox = outbox;
        this.liquidity = liquidity;
        this.narration = narration;
        this.lookup = lookup;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.Negotiation cfg() {
        return props.getFormulas().getNegotiation();
    }

    // ------------------------------------------------------------------------------------------ guards

    /** "One field, one negotiation": blocks every new start while an OPEN negotiation exists for the asset. */
    public boolean isBlocked(Savegame sg, AssetType type, String assetId) {
        return negotiations.existsBySavegameAndAssetTypeAndAssetIdAndStatus(sg, type, assetId, NegotiationStatus.OPEN);
    }

    private void requireFree(Savegame sg, int farmlandId) {
        if (isBlocked(sg, AssetType.FARMLAND, String.valueOf(farmlandId))) {
            throw new BusinessRuleException("FIELD_IN_NEGOTIATION",
                    "Für dieses Feld läuft bereits eine Versteigerung oder Verhandlung.");
        }
    }

    private Negotiation newNegotiation(Savegame sg, NegotiationKind kind, NegotiationDirection dir, Initiator by,
                                       FarmlandOwnership field) {
        Negotiation n = new Negotiation();
        n.setSavegame(sg);
        n.setAssetType(AssetType.FARMLAND);
        n.setAssetId(String.valueOf(field.getFarmlandId()));
        n.setKind(kind);
        n.setDirection(dir);
        n.setInitiatedBy(by);
        n.setStatus(NegotiationStatus.OPEN);
        n.setBasePrice(field.getReferencePrice());
        n.setMaxRounds(cfg().getMaxRounds());
        n.setOpenedAtGameTime(sg.getCurrentGameTime());
        return n;
    }

    private NegotiationOffer offer(Negotiation n, int round, OfferParty party, Character c, long amount, OfferResult result,
                                   Long counter, Long hiddenMaxBid) {
        NegotiationOffer o = new NegotiationOffer();
        o.setSavegame(n.getSavegame());
        o.setNegotiation(n);
        o.setRoundNumber(round);
        o.setOfferedBy(party);
        o.setCharacter(c);
        o.setAmount(amount);
        o.setResult(result);
        o.setCounterAmount(counter);
        o.setHiddenMaxBid(hiddenMaxBid);
        o.setGameTime(n.getSavegame().getCurrentGameTime());
        return offers.save(o);
    }

    // ------------------------------------------------------------------------------------------ auction

    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        expireDeadlines(sg);
        if (random.chance(cfg().getAuctionDailySpawnProbability())) {
            startAuction(sg);
        }
    }

    /** System-initiated auction of an unowned field or of a field of a willing NPC seller. */
    @Transactional
    public Optional<Negotiation> startAuction(Savegame sg) {
        List<FarmlandOwnership> candidates = ownership.list(sg).stream()
                .filter(o -> o.getOwnerType() == OwnerType.UNCLAIMED
                        || (o.getOwnerType() == OwnerType.CHARACTER && o.getOwnerCharacter().isSellWilling()
                        && o.getOwnerCharacter().getStatus() == CharacterStatus.ACTIVE))
                .filter(o -> o.getReferencePrice() > 0)
                .filter(o -> !isBlocked(sg, AssetType.FARMLAND, String.valueOf(o.getFarmlandId())))
                .toList();
        Optional<Character> announcer = lookup.firstActive(sg, CharacterRole.LAND_AGENT, CharacterRole.COOPERATIVE);
        if (candidates.isEmpty() || announcer.isEmpty()) {
            return Optional.empty();
        }
        FarmlandOwnership field = random.pick(candidates);
        Negotiation n = newNegotiation(sg, NegotiationKind.AUCTION, NegotiationDirection.PLAYER_BUYS, Initiator.SYSTEM, field);
        n.setAnnouncingCharacter(announcer.get());
        n.setCounterpartCharacter(field.getOwnerCharacter());
        n.setClosesAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAuctionDurationDays()));
        negotiations.save(n);
        List<Character> bidders = new ArrayList<>(lookup.activeDynamic(sg));
        bidders.removeIf(c -> field.getOwnerCharacter() != null && c.getId().equals(field.getOwnerCharacter().getId()));
        int count = Math.min(bidders.size(), random.intBetween(cfg().getAuctionBiddersMin(), cfg().getAuctionBiddersMax()));
        java.util.Collections.shuffle(bidders, new java.util.Random(random.nextLong()));
        for (Character b : bidders.subList(0, count)) {
            long max = NegotiationFormula.npcMaxBid(n.getBasePrice(), b.getGenerationSeed() ^ (31L * n.getId()), cfg());
            offer(n, 0, OfferParty.CHARACTER, b, 0, OfferResult.BID, null, max);
        }
        narration.request(sg, NarrationEventType.AUCTION_ANNOUNCED).from(announcer.get())
                .facts(NarrationFacts.builder().put("farmlandId", field.getFarmlandId())
                        .put("hectares", field.getHectares()).put("estimatedValue", field.getReferencePrice())
                        .put("closesInDays", cfg().getAuctionDurationDays()).put("bidderCount", count)
                        .put("sellerName", field.getOwnerCharacter() == null ? null : field.getOwnerCharacter().getName())
                        .build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId())
                .formLink("/farmland?negotiation=" + n.getId()).submit();
        return Optional.of(n);
    }

    // ------------------------------------------------------------------------------------------ direct

    /** Player-initiated direct negotiation with a character that owns the field (no co-bidders). */
    @Transactional
    public Negotiation startDirect(Savegame sg, Long characterId, int farmlandId) {
        FarmlandOwnership field = ownership.get(sg, farmlandId)
                .orElseThrow(() -> new NotFoundException("farmland " + farmlandId));
        if (field.getOwnerType() != OwnerType.CHARACTER || !field.getOwnerCharacter().getId().equals(characterId)) {
            throw new BusinessRuleException("NOT_OWNER", "Dieser Charakter besitzt das Feld nicht.");
        }
        if (field.getOwnerCharacter().getStatus() != CharacterStatus.ACTIVE) {
            throw new BusinessRuleException("CHARACTER_INACTIVE", "Der Charakter ist derzeit nicht erreichbar.");
        }
        requireFree(sg, farmlandId);
        Negotiation n = newNegotiation(sg, NegotiationKind.DIRECT, NegotiationDirection.PLAYER_BUYS, Initiator.PLAYER, field);
        n.setCounterpartCharacter(field.getOwnerCharacter());
        return negotiations.save(n);
    }

    // ------------------------------------------------------------------------------------------ sale of own field

    /** The player offers an own field with an asking price; 0..n interested NPCs respond with a first offer. */
    @Transactional
    public List<Negotiation> createSaleOffer(Savegame sg, int farmlandId, long askingPrice) {
        FarmlandOwnership field = ownership.get(sg, farmlandId)
                .orElseThrow(() -> new NotFoundException("farmland " + farmlandId));
        if (field.getOwnerType() != OwnerType.PLAYER) {
            throw new BusinessRuleException("NOT_PLAYER_FIELD", "Nur eigene Felder können verkauft werden.");
        }
        if (askingPrice <= 0) {
            throw new BusinessRuleException("INVALID_PRICE", "Der Wunschpreis muss positiv sein.");
        }
        requireFree(sg, farmlandId);
        List<Character> interested = lookup.activeDynamic(sg).stream()
                .filter(c -> c.getVirtualWealth() >= askingPrice * cfg().getInterestWealthFactor())
                .sorted(Comparator.comparing(Character::getId))
                .limit(cfg().getMaxInterestedBuyers())
                .toList();
        String group = "sale_" + UUID.randomUUID().toString().substring(0, 8);
        List<Negotiation> result = new ArrayList<>();
        for (Character buyer : interested) {
            Negotiation n = newNegotiation(sg, NegotiationKind.SALE_OFFER, NegotiationDirection.PLAYER_SELLS,
                    Initiator.CHARACTER, field);
            n.setCounterpartCharacter(buyer);
            n.setAskingPrice(askingPrice);
            n.setSaleGroupId(group);
            n.setClosesAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getSaleOfferValidDays()));
            negotiations.save(n);
            long limit = NegotiationFormula.effectiveMaxAccept(n.getBasePrice(), buyer.getNegotiationTrait(),
                    trust.getCurrentTrust(buyer), cfg());
            long first = NegotiationFormula.npcCounterOffer(askingPrice, limit, buyer.getGenerationSeed() ^ n.getId(), cfg());
            n.setLastCounterOffer(first);
            offer(n, 0, OfferParty.CHARACTER, buyer, first, OfferResult.BID, null, null);
            narration.request(sg, NarrationEventType.SALE_OFFER_RECEIVED).from(buyer)
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("hectares", field.getHectares())
                            .put("askingPrice", askingPrice).put("offer", first).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId())
                    .formLink("/farmland?negotiation=" + n.getId()).submit();
            result.add(n);
        }
        if (interested.isEmpty()) {
            narration.request(sg, NarrationEventType.SALE_NO_INTEREST)
                    .from(lookup.firstActive(sg, CharacterRole.LAND_AGENT, CharacterRole.COOPERATIVE).orElse(null))
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("askingPrice", askingPrice).build())
                    .category(CommunicationCategory.NEGOTIATION).submit();
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------ offers

    public record OfferOutcome(Negotiation negotiation, OfferResult result, Long counterAmount, int roundsLeft) {
    }

    /** Form-based offer (never a number from free text). */
    @Transactional
    public OfferOutcome placeOffer(Savegame sg, Long negotiationId, long amount) {
        Negotiation n = get(sg, negotiationId);
        if (n.getStatus() != NegotiationStatus.OPEN) {
            throw new BusinessRuleException("NEGOTIATION_CLOSED", "Diese Verhandlung ist bereits beendet.");
        }
        if (n.getRoundsUsed() >= n.getMaxRounds()) {
            throw new BusinessRuleException("NO_ROUNDS_LEFT", "Alle Verhandlungsrunden sind aufgebraucht.");
        }
        if (amount <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Das Gebot muss positiv sein.");
        }
        if (n.getDirection() == NegotiationDirection.PLAYER_BUYS && liquidity.available(sg) < amount) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Das Gebot übersteigt den verfügbaren Kontostand.");
        }
        n.setRoundsUsed(n.getRoundsUsed() + 1);
        return switch (n.getKind()) {
            case AUCTION -> auctionBid(sg, n, amount);
            case DIRECT -> directOffer(sg, n, amount);
            case SALE_OFFER -> saleDemand(sg, n, amount);
        };
    }

    private OfferOutcome auctionBid(Savegame sg, Negotiation n, long amount) {
        List<NegotiationOffer> npcBids = offers.findByNegotiationOrderByIdAsc(n).stream()
                .filter(o -> o.getOfferedBy() == OfferParty.CHARACTER && o.getHiddenMaxBid() != null).toList();
        Optional<NegotiationOffer> top = npcBids.stream().max(Comparator.comparing(NegotiationOffer::getHiddenMaxBid));
        long topMax = top.map(NegotiationOffer::getHiddenMaxBid).orElse(0L);
        boolean wins = amount > topMax
                || (amount == topMax && trust.getCurrentTrust(n.getAnnouncingCharacter()) >= cfg().getAuctionTieTrustThreshold());
        int left = n.getMaxRounds() - n.getRoundsUsed();
        if (wins) {
            offer(n, n.getRoundsUsed(), OfferParty.PLAYER, null, amount, OfferResult.ACCEPTED, null, null);
            closeWon(sg, n, amount, NarrationEventType.AUCTION_WON, n.getAnnouncingCharacter());
            return new OfferOutcome(n, OfferResult.ACCEPTED, null, left);
        }
        long leading = NegotiationFormula.leadingBid(amount, topMax, n.getBasePrice(), cfg());
        n.setLastCounterOffer(leading);
        offer(n, n.getRoundsUsed(), OfferParty.PLAYER, null, amount, OfferResult.OUTBID, leading, null);
        if (left <= 0) {
            closeLost(sg, n, top.map(NegotiationOffer::getCharacter).orElse(null), leading);
            return new OfferOutcome(n, OfferResult.REJECTED, leading, 0);
        }
        narration.request(sg, NarrationEventType.NEGOTIATION_COUNTER).from(n.getAnnouncingCharacter())
                .facts(NarrationFacts.builder().put("kind", n.getKind()).put("result", OfferResult.OUTBID)
                        .put("playerOffer", amount).put("leadingBid", leading).put("roundsLeft", left).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId()).submit();
        return new OfferOutcome(n, OfferResult.OUTBID, leading, left);
    }

    private OfferOutcome directOffer(Savegame sg, Negotiation n, long amount) {
        Character seller = n.getCounterpartCharacter();
        long effMin = NegotiationFormula.effectiveMinAccept(n.getBasePrice(), seller.getNegotiationTrait(),
                trust.getCurrentTrust(seller), cfg());
        OfferResult r = NegotiationFormula.evaluatePurchase(amount, effMin, cfg());
        return respond(sg, n, amount, r, r == OfferResult.COUNTER ? effMin : null, seller);
    }

    private OfferOutcome saleDemand(Savegame sg, Negotiation n, long demand) {
        Character buyer = n.getCounterpartCharacter();
        long effMax = NegotiationFormula.effectiveMaxAccept(n.getBasePrice(), buyer.getNegotiationTrait(),
                trust.getCurrentTrust(buyer), cfg());
        OfferResult r = NegotiationFormula.evaluateSale(demand, effMax, cfg());
        return respond(sg, n, demand, r, r == OfferResult.COUNTER ? effMax : null, buyer);
    }

    private OfferOutcome respond(Savegame sg, Negotiation n, long amount, OfferResult r, Long counter, Character npc) {
        int left = n.getMaxRounds() - n.getRoundsUsed();
        offer(n, n.getRoundsUsed(), OfferParty.PLAYER, null, amount, r, counter, null);
        if (r == OfferResult.ACCEPTED) {
            trust.recordEvent(npc, props.getFormulas().getTrust().getNegotiationDeal(), TrustReason.NEGOTIATION_DEAL,
                    "Einigung Feld " + n.getAssetId());
            closeWon(sg, n, amount, NarrationEventType.NEGOTIATION_ACCEPTED, npc);
            return new OfferOutcome(n, r, null, left);
        }
        if (counter != null) {
            n.setLastCounterOffer(counter);
        }
        boolean finalRound = left <= 0;
        if (finalRound) {
            n.setStatus(NegotiationStatus.REJECTED);
            n.setClosedAtGameTime(sg.getCurrentGameTime());
        }
        NarrationEventType type = r == OfferResult.COUNTER && !finalRound ? NarrationEventType.NEGOTIATION_COUNTER
                : NarrationEventType.NEGOTIATION_REJECTED;
        narration.request(sg, type).from(npc)
                .facts(NarrationFacts.builder().put("kind", n.getKind()).put("result", finalRound ? OfferResult.REJECTED : r)
                        .put("playerOffer", amount).put("counterOffer", finalRound ? null : counter)
                        .put("roundsLeft", left).put("final", finalRound).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId()).submit();
        return new OfferOutcome(n, finalRound ? OfferResult.REJECTED : r, counter, left);
    }

    /**
     * Agreement: FARMLAND_TRANSFER and MONEY_TRANSACTION are written as ONE batch so ownership and money never
     * diverge; the tool ownership table follows immediately.
     */
    private void closeWon(Savegame sg, Negotiation n, long price, NarrationEventType type, Character narrator) {
        n.setStatus(NegotiationStatus.ACCEPTED);
        n.setFinalPrice(price);
        n.setClosedAtGameTime(sg.getCurrentGameTime());
        int farmlandId = Integer.parseInt(n.getAssetId());
        boolean toPlayer = n.getDirection() == NegotiationDirection.PLAYER_BUYS;
        outbox.farmlandDeal(sg, farmlandId, toPlayer, price, (toPlayer ? "Kauf" : "Verkauf") + " Feld " + farmlandId,
                new Related(RELATED, n.getId()));
        if (toPlayer) {
            ownership.setOwner(sg, farmlandId, OwnerType.PLAYER, null);
        } else {
            ownership.setOwner(sg, farmlandId, OwnerType.CHARACTER, n.getCounterpartCharacter());
            n.getCounterpartCharacter().setVirtualWealth(Math.max(0, n.getCounterpartCharacter().getVirtualWealth() - price));
            if (n.getSaleGroupId() != null) {
                negotiations.findBySaleGroupId(n.getSaleGroupId()).stream()
                        .filter(o -> !o.getId().equals(n.getId()) && o.getStatus() == NegotiationStatus.OPEN)
                        .forEach(o -> {
                            o.setStatus(NegotiationStatus.EXPIRED);
                            o.setClosedAtGameTime(sg.getCurrentGameTime());
                        });
            }
        }
        narration.request(sg, type).from(narrator)
                .facts(NarrationFacts.builder().put("kind", n.getKind()).put("result", OfferResult.ACCEPTED)
                        .put("farmlandId", farmlandId).put("finalPrice", price).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", toPlayer ? "Feld " + farmlandId + " gekauft" : "Feld " + farmlandId + " verkauft",
                (toPlayer ? "Kaufpreis: " : "Verkaufspreis: ") + price + " €", RELATED, n.getId());
    }

    private void closeLost(Savegame sg, Negotiation n, Character winner, long price) {
        n.setStatus(NegotiationStatus.LOST);
        n.setWinnerCharacter(winner);
        n.setFinalPrice(price);
        n.setClosedAtGameTime(sg.getCurrentGameTime());
        if (winner != null) {
            ownership.setOwner(sg, Integer.parseInt(n.getAssetId()), OwnerType.CHARACTER, winner);
        }
        narration.request(sg, NarrationEventType.AUCTION_LOST).from(n.getAnnouncingCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", n.getAssetId())
                        .put("winnerName", winner == null ? null : winner.getName()).put("finalPrice", price).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, n.getId()).submit();
    }

    /** Player withdraws (for a sale offer the whole sale process is withdrawn). */
    @Transactional
    public Negotiation withdraw(Savegame sg, Long negotiationId) {
        Negotiation n = get(sg, negotiationId);
        if (n.getStatus() != NegotiationStatus.OPEN) {
            throw new BusinessRuleException("NEGOTIATION_CLOSED", "Diese Verhandlung ist bereits beendet.");
        }
        List<Negotiation> all = n.getSaleGroupId() == null ? List.of(n) : negotiations.findBySaleGroupId(n.getSaleGroupId());
        for (Negotiation x : all) {
            if (x.getStatus() == NegotiationStatus.OPEN) {
                x.setStatus(NegotiationStatus.WITHDRAWN);
                x.setClosedAtGameTime(sg.getCurrentGameTime());
            }
        }
        return n;
    }

    @Transactional
    public void expireDeadlines(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Negotiation n : negotiations.findBySavegameAndStatus(sg, NegotiationStatus.OPEN)) {
            if (n.getClosesAtGameTime() == null || n.getClosesAtGameTime() > now) {
                continue;
            }
            if (n.getKind() == NegotiationKind.AUCTION) {
                Optional<NegotiationOffer> top = offers.findByNegotiationOrderByIdAsc(n).stream()
                        .filter(o -> o.getHiddenMaxBid() != null).max(Comparator.comparing(NegotiationOffer::getHiddenMaxBid));
                long price = n.getLastCounterOffer() != null ? n.getLastCounterOffer()
                        : Math.round(n.getBasePrice() * cfg().getNpcBidMin());
                closeLost(sg, n, top.map(NegotiationOffer::getCharacter).orElse(null),
                        Math.min(price, top.map(NegotiationOffer::getHiddenMaxBid).orElse(price)));
            } else {
                n.setStatus(NegotiationStatus.EXPIRED);
                n.setClosedAtGameTime(now);
            }
        }
    }

    public Negotiation get(Savegame sg, Long id) {
        return negotiations.findById(id).filter(n -> n.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("negotiation " + id));
    }

    public List<Negotiation> list(Savegame sg) {
        return negotiations.findBySavegameOrderByIdDesc(sg);
    }

    /** Offer history for the UI - NPC limits (hiddenMaxBid) are never part of it. */
    public List<NegotiationOffer> visibleOffers(Negotiation n) {
        return offers.findByNegotiationOrderByIdAsc(n).stream().filter(o -> o.getOfferedBy() == OfferParty.PLAYER
                || o.getHiddenMaxBid() == null).toList();
    }
}
