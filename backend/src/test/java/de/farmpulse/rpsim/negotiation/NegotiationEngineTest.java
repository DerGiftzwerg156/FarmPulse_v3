package de.farmpulse.rpsim.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.OfferResult;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class NegotiationEngineTest {

    @Autowired Fixtures fx;
    @Autowired NegotiationEngine engine;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;

    Savegame sg;
    Character seller;
    Character richNeighbor;
    Character landAgent;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()).replace(
                "{ \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000, \"ownerFarmId\": 0 }",
                "{ \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000, \"ownerFarmId\": 0 },"
                        + "{ \"farmlandId\": 14, \"hectares\": 8, \"price\": 100000, \"ownerFarmId\": 0 }"));
        landAgent = fx.character(sg, CharacterRole.LAND_AGENT, CharacterCategory.MANDATORY, "Landhändler Kuhn");
        seller = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        seller.setNegotiationTrait(NegotiationTrait.NEUTRAL);
        seller.setSellWilling(true);
        richNeighbor = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bäuerin Albers");
        richNeighbor.setVirtualWealth(1_000_000);
        seller.setVirtualWealth(10_000);
        fx.snapshot(sg, 1_000_000); // player owns farmland 12
        ownership.reconcile(sg);
        ownership.setOwner(sg, 13, OwnerType.UNCLAIMED, null);
        ownership.setOwner(sg, 14, OwnerType.CHARACTER, seller);
    }

    private List<OutboxInstruction> instructions() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    @Test
    void ownershipTableIsBuiltFromExport() {
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(ownership.get(sg, 14).orElseThrow().getReferencePrice()).isEqualTo(100_000);
    }

    @Test
    void directNegotiationEndsAfterThreeRounds() {
        Negotiation n = engine.startDirect(sg, seller.getId(), 14);
        assertThat(engine.placeOffer(sg, n.getId(), 10_000).result()).isEqualTo(OfferResult.REJECTED);
        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.OPEN);
        engine.placeOffer(sg, n.getId(), 10_000);
        engine.placeOffer(sg, n.getId(), 10_000);
        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.REJECTED);
        assertThatThrownBy(() -> engine.placeOffer(sg, n.getId(), 10_000)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void counterOfferThenAcceptanceCreatesAtomicBatch() {
        Negotiation n = engine.startDirect(sg, seller.getId(), 14);
        // neutral trait: minAccept 88 000, trust 0 -> counter band [79 200, 88 000)
        NegotiationEngine.OfferOutcome o = engine.placeOffer(sg, n.getId(), 85_000);
        assertThat(o.result()).isEqualTo(OfferResult.COUNTER);
        assertThat(o.counterAmount()).isEqualTo(88_000);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("88000")
                .doesNotContainIgnoringCase("minAccept");
        NegotiationEngine.OfferOutcome ok = engine.placeOffer(sg, n.getId(), 88_000);
        assertThat(ok.result()).isEqualTo(OfferResult.ACCEPTED);
        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.ACCEPTED);
        List<OutboxInstruction> batch = instructions();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getBatchId()).isEqualTo(batch.get(1).getBatchId());
        assertThat(batch.get(0).getPayloadJson()).contains("\"TO_PLAYER\"").contains("\"farmlandId\":14");
        assertThat(batch.get(1).getPayloadJson()).contains("FARMLAND_PURCHASE").contains("-88000");
        assertThat(ownership.get(sg, 14).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(seller)).extracting(e -> e.getReason())
                .contains(TrustReason.NEGOTIATION_DEAL);
    }

    @Test
    void oneFieldOneNegotiation() {
        engine.startDirect(sg, seller.getId(), 14);
        assertThatThrownBy(() -> engine.startDirect(sg, seller.getId(), 14))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("bereits");
        // the auction spawner never picks the blocked field
        for (int i = 0; i < 20; i++) {
            engine.startAuction(sg).ifPresent(a -> assertThat(a.getAssetId()).isNotEqualTo("14"));
        }
    }

    @Test
    void directNegotiationOnlyWithOwner() {
        assertThatThrownBy(() -> engine.startDirect(sg, richNeighbor.getId(), 14)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void auctionWonWithBidAboveAllNpcLimits() {
        Negotiation a = engine.startAuction(sg).orElseThrow();
        long limit = Math.round(a.getBasePrice() * 1.15) + 1;
        NegotiationEngine.OfferOutcome o = engine.placeOffer(sg, a.getId(), limit);
        assertThat(o.result()).isEqualTo(OfferResult.ACCEPTED);
        assertThat(a.getStatus()).isEqualTo(NegotiationStatus.ACCEPTED);
        assertThat(instructions()).hasSize(2);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(j -> j.getEventType())
                .contains("AUCTION_ANNOUNCED", "AUCTION_WON");
    }

    @Test
    void auctionLostAfterThreeLowBidsAndNpcBecomesOwner() {
        Negotiation a = engine.startAuction(sg).orElseThrow();
        long low = Math.round(a.getBasePrice() * 0.5);
        NegotiationEngine.OfferOutcome first = engine.placeOffer(sg, a.getId(), low);
        assertThat(first.result()).isEqualTo(OfferResult.OUTBID);
        assertThat(first.counterAmount()).isGreaterThan(low);
        engine.placeOffer(sg, a.getId(), low);
        engine.placeOffer(sg, a.getId(), low);
        assertThat(a.getStatus()).isEqualTo(NegotiationStatus.LOST);
        assertThat(a.getWinnerCharacter()).isNotNull();
        FarmlandOwnership field = ownership.get(sg, Integer.parseInt(a.getAssetId())).orElseThrow();
        assertThat(field.getOwnerType()).isEqualTo(OwnerType.CHARACTER);
        assertThat(instructions()).isEmpty();
        assertThat(engine.visibleOffers(a)).allMatch(o -> o.getHiddenMaxBid() == null);
    }

    @Test
    void saleOfOwnFieldWithInterestedBuyer() {
        List<Negotiation> offers = engine.createSaleOffer(sg, 12, 60_000);
        assertThat(offers).hasSize(1); // only the rich neighbour can afford it
        Negotiation n = offers.get(0);
        assertThat(n.getCounterpartCharacter().getId()).isEqualTo(richNeighbor.getId());
        assertThat(n.getLastCounterOffer()).isBetween(51_000L, 60_000L);
        NegotiationEngine.OfferOutcome o = engine.placeOffer(sg, n.getId(), n.getLastCounterOffer());
        assertThat(o.result()).isEqualTo(OfferResult.ACCEPTED);
        List<OutboxInstruction> batch = instructions();
        assertThat(batch.get(0).getPayloadJson()).contains("\"FROM_PLAYER\"");
        assertThat(batch.get(1).getPayloadJson()).contains("FARMLAND_SALE").contains("\"amount\":" + n.getLastCounterOffer());
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerCharacter().getId()).isEqualTo(richNeighbor.getId());
    }

    @Test
    void saleWithoutInterestedBuyers() {
        assertThat(engine.createSaleOffer(sg, 12, 50_000_000)).isEmpty();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(j -> j.getEventType()).contains("SALE_NO_INTEREST");
    }

    @Test
    void cannotSellForeignFieldOrOfferMoreThanAvailable() {
        assertThatThrownBy(() -> engine.createSaleOffer(sg, 14, 1000)).isInstanceOf(BusinessRuleException.class);
        Negotiation n = engine.startDirect(sg, seller.getId(), 14);
        assertThatThrownBy(() -> engine.placeOffer(sg, n.getId(), 50_000_000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Kontostand");
    }

    @Test
    void vanillaPurchaseAndSaleAreFollowedUpSilently() {
        // FS25 export now shows farmland 13 as own field and 12 no longer (bought/sold in the vanilla menu)
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 1_000_000)
                .replace("\"farmlandId\": 12", "\"farmlandId\": 13");
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 1_000_000, facts);
        ownership.reconcile(sg);
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerType()).isEqualTo(OwnerType.UNCLAIMED);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }

    @Test
    void pendingTransferIsNotReconciledAway() {
        Negotiation n = engine.startDirect(sg, seller.getId(), 14);
        engine.placeOffer(sg, n.getId(), 100_000);
        ownership.reconcile(sg); // the mod has not applied the transfer yet
        assertThat(ownership.get(sg, 14).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
    }

    @Test
    void withdrawClosesNegotiation() {
        Negotiation n = engine.startDirect(sg, seller.getId(), 14);
        engine.withdraw(sg, n.getId());
        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.WITHDRAWN);
        assertThat(engine.isBlocked(sg, n.getAssetType(), "14")).isFalse();
    }
}
