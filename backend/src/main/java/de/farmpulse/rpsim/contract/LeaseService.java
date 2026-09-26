package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-22 lease of NPC fields (not in vanilla). The owner character offers a lease on request; on acceptance the
 * game gives the field to the player (FARMLAND_TRANSFER TO_PLAYER), in the tool it stays the character's field
 * ({@link FarmlandOwnership#isLeasedToPlayer()}). The rent is billed monthly (LEASE_PAYMENT, ContractBillingService).
 * One month before the end the owner writes with a renewal (new rent) and, if willing to sell, a purchase offer;
 * without an answer the field goes back automatically (FARMLAND_TRANSFER FROM_PLAYER).
 */
@Service
public class LeaseService {

    /** Related type of the transfers and of the purchase booking (the rent uses ContractBillingService.RELATED). */
    public static final String RELATED = "LEASE";

    private final ContractRepository contracts;
    private final SavegameRepository savegames;
    private final ContractBillingService billing;
    private final FarmlandOwnershipService ownership;
    private final NegotiationEngine negotiations;
    private final OutboxService outbox;
    private final LiquidityService liquidity;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public LeaseService(ContractRepository contracts, SavegameRepository savegames, ContractBillingService billing,
                        FarmlandOwnershipService ownership, NegotiationEngine negotiations, OutboxService outbox,
                        LiquidityService liquidity, TrustScoreService trust, NarrationRequestService narration,
                        DiaryService diary, RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.savegames = savegames;
        this.billing = billing;
        this.ownership = ownership;
        this.negotiations = negotiations;
        this.outbox = outbox;
        this.liquidity = liquidity;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Lease cfg() {
        return props.getFormulas().getLease();
    }

    /** Monthly rent: reference price × annual share / 12, 10 % cheaper at trust +100 (dearer at −100). */
    public static long monthlyRent(long referencePrice, double trust, RpsimProperties.Lease cfg) {
        double factor = 1 - Math.max(-1, Math.min(1, trust / 100.0)) * cfg.getTrustInfluence();
        return Math.max(10, round10(referencePrice * cfg.getAnnualRentShare() / 12.0 * factor));
    }

    private List<Contract> leases(Savegame sg, ContractStatus... statuses) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.LEASE, List.of(statuses));
    }

    public Optional<Contract> openFor(Savegame sg, int farmlandId) {
        return leases(sg, ContractStatus.OFFERED, ContractStatus.ACTIVE).stream()
                .filter(c -> c.getFarmlandId() != null && c.getFarmlandId() == farmlandId).findFirst();
    }

    // ------------------------------------------------------------------------------------------ offer

    /** The player asks the owner of a field for a lease. The owner answers with an offer or a refusal. */
    @Transactional
    public Contract requestOffer(Savegame sg, int farmlandId) {
        FarmlandOwnership field = ownership.get(sg, farmlandId)
                .orElseThrow(() -> new NotFoundException("farmland " + farmlandId));
        if (!field.isTradeable()) {
            throw new BusinessRuleException("FIELD_NOT_TRADEABLE", "Diese Fläche kann im Spiel nicht gehandelt werden.");
        }
        if (field.getOwnerType() != OwnerType.CHARACTER || field.getOwnerCharacter() == null) {
            throw new BusinessRuleException("NOT_LEASABLE", "Nur Felder, die jemandem aus dem Dorf gehören, lassen sich pachten.");
        }
        Character owner = field.getOwnerCharacter();
        if (owner.getStatus() != CharacterStatus.ACTIVE) {
            throw new BusinessRuleException("CHARACTER_INACTIVE", "Der Besitzer ist derzeit nicht erreichbar.");
        }
        if (field.isLeasedToPlayer() || openFor(sg, farmlandId).isPresent()) {
            throw new BusinessRuleException("LEASE_EXISTS", "Für dieses Feld gibt es bereits ein Pachtangebot oder eine Pacht.");
        }
        if (negotiations.isBlocked(sg, AssetType.FARMLAND, String.valueOf(farmlandId))) {
            throw new BusinessRuleException("FIELD_IN_NEGOTIATION", "Für dieses Feld läuft gerade eine Verhandlung.");
        }
        double t = trust.getCurrentTrust(owner);
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.LEASE);
        c.setCharacter(owner);
        c.setFarmlandId(farmlandId);
        c.setTermMonths(cfg().getTermMonths());
        c.setMonthlyAmount(monthlyRent(field.getReferencePrice(), t, cfg()));
        c.setCreatedAt(Instant.now());
        double accept = cfg().getAcceptProbability() + Math.max(-1, Math.min(1, t / 100.0)) * cfg().getAcceptTrustInfluence();
        if (!random.chance(accept)) {
            c.setStatus(ContractStatus.DECLINED);
            c.setEndReason("OWNER_REFUSED");
            contracts.save(c);
            narration.request(sg, NarrationEventType.LEASE_REFUSED).from(owner)
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).build())
                    .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
            return c;
        }
        c.setStatus(ContractStatus.OFFERED);
        c.setOfferExpiresAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getOfferValidDays()));
        contracts.save(c);
        narration.request(sg, NarrationEventType.LEASE_OFFER).from(owner)
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("hectares", field.getHectares())
                        .put("monthlyRent", c.getMonthlyAmount()).put("termMonths", c.getTermMonths())
                        .put("validDays", Math.round(cfg().getOfferValidDays())).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/contracts?contract=" + c.getId()).submit();
        return c;
    }

    Contract own(Savegame sg, Long id) {
        return contracts.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()) && c.getKind() == ContractKind.LEASE)
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    /** Acceptance: rent from the next month on, the game gives the field to the player. */
    @Transactional
    public Contract accept(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < sg.getCurrentGameTime()) {
            throw new BusinessRuleException("OFFER_EXPIRED", "Das Angebot ist abgelaufen.");
        }
        FarmlandOwnership field = ownership.get(sg, c.getFarmlandId()).orElseThrow();
        if (field.getOwnerType() != OwnerType.CHARACTER || field.getOwnerCharacter() == null
                || !field.getOwnerCharacter().getId().equals(c.getCharacter().getId())
                || negotiations.isBlocked(sg, AssetType.FARMLAND, String.valueOf(c.getFarmlandId()))) {
            throw new BusinessRuleException("FIELD_CHANGED", "Das Feld ist inzwischen nicht mehr verfügbar.");
        }
        billing.activate(sg, c);
        field.setLeasedToPlayer(true);
        outbox.farmlandTransfer(sg, c.getFarmlandId(), true, "Pacht Feld " + c.getFarmlandId(), new Related(RELATED, c.getId()));
        narration.request(sg, NarrationEventType.LEASE_CONFIRMED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("monthlyRent", c.getMonthlyAmount())
                        .put("termMonths", c.getTermMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Feld " + c.getFarmlandId() + " gepachtet", c.getTermMonths() + " Monate, "
                + c.getMonthlyAmount() + " € pro Monat bei " + c.getCharacter().getName() + ".", ContractBillingService.RELATED,
                c.getId());
        return c;
    }

    @Transactional
    public Contract decline(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        c.setStatus(ContractStatus.DECLINED);
        c.setEndReason("PLAYER");
        return c;
    }

    // ------------------------------------------------------------------------------------------ end of term

    /** Renewal at the offered rent for another term. */
    @Transactional
    public Contract renew(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.ACTIVE || c.getRenewalAmount() == null) {
            throw new BusinessRuleException("NO_RENEWAL_OFFER", "Es liegt kein Verlängerungsangebot vor.");
        }
        c.setMonthlyAmount(c.getRenewalAmount());
        c.setEndsAtGameTime(gameTime.addMonths(sg, c.getEndsAtGameTime(), c.getTermMonths()));
        c.setRenewalAmount(null);
        c.setPurchasePrice(null);
        c.setRenewalOffered(false);
        narration.request(sg, NarrationEventType.LEASE_RENEWED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("monthlyRent", c.getMonthlyAmount())
                        .put("termMonths", c.getTermMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Pacht verlängert", "Feld " + c.getFarmlandId() + ": weitere " + c.getTermMonths()
                + " Monate, " + c.getMonthlyAmount() + " € pro Monat.", ContractBillingService.RELATED, c.getId());
        return c;
    }

    /**
     * Purchase of the leased field at the offered price. The game already shows the field as the player's, so only
     * the money is booked (FARMLAND_PURCHASE); if the mod refuses the booking the lease continues (onInstructionFailed).
     */
    @Transactional
    public Contract buy(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.ACTIVE || c.getPurchasePrice() == null) {
            throw new BusinessRuleException("NO_PURCHASE_OFFER", "Es liegt kein Kaufangebot vor.");
        }
        if (liquidity.available(sg) < c.getPurchasePrice()) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Dein Kontostand reicht für den Kauf nicht.");
        }
        FarmlandOwnership field = ownership.get(sg, c.getFarmlandId()).orElseThrow();
        c.setStatus(ContractStatus.ENDED);
        c.setEndReason("PURCHASED");
        field.setLeasedToPlayer(false);
        ownership.setOwner(sg, c.getFarmlandId(), OwnerType.PLAYER, null);
        outbox.money(sg, -c.getPurchasePrice(), MoneyReason.FARMLAND_PURCHASE, "Kauf Feld " + c.getFarmlandId(),
                new Related(RELATED, c.getId()));
        narration.request(sg, NarrationEventType.LEASE_PURCHASED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("price", c.getPurchasePrice()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Feld " + c.getFarmlandId() + " gekauft", "Gepachtetes Feld für " + c.getPurchasePrice()
                + " € übernommen.", ContractBillingService.RELATED, c.getId());
        return c;
    }

    /** The player ends the lease early: the field goes back now, no refund. */
    @Transactional
    public Contract cancel(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessRuleException("NOT_ACTIVE", "Diese Pacht läuft nicht.");
        }
        giveBack(sg, c, ContractStatus.CANCELLED, "EARLY_RETURN");
        return c;
    }

    private void giveBack(Savegame sg, Contract c, ContractStatus status, String reason) {
        c.setStatus(status);
        c.setEndReason(reason);
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        c.setRenewalAmount(null);
        c.setPurchasePrice(null);
        ownership.get(sg, c.getFarmlandId()).ifPresent(f -> f.setLeasedToPlayer(false));
        outbox.farmlandTransfer(sg, c.getFarmlandId(), false, "Pachtende Feld " + c.getFarmlandId(),
                new Related(RELATED, c.getId()));
        narration.request(sg, NarrationEventType.LEASE_ENDED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("reason", reason).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Pacht beendet", "Feld " + c.getFarmlandId() + " geht an "
                + c.getCharacter().getName() + " zurück.", ContractBillingService.RELATED, c.getId());
    }

    // ------------------------------------------------------------------------------------------ daily

    @EventListener
    @Order(74)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        check(savegames.findById(e.savegameId()).orElseThrow());
    }

    @Transactional
    public void check(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Contract c : leases(sg, ContractStatus.OFFERED)) {
            if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < now) {
                c.setStatus(ContractStatus.DECLINED);
                c.setEndReason("EXPIRED");
            }
        }
        for (Contract c : leases(sg, ContractStatus.ACTIVE)) {
            if (c.getEndsAtGameTime() == null) {
                continue;
            }
            if (now >= c.getEndsAtGameTime()) {
                giveBack(sg, c, ContractStatus.ENDED, "TERM_ENDED");
            } else if (!c.isRenewalOffered()
                    && now >= gameTime.addMonths(sg, c.getEndsAtGameTime(), -cfg().getWarningMonths())) {
                warn(sg, c);
            }
        }
    }

    /** One month before the end: renewal at a new rent and - if the owner is willing to sell - a purchase offer. */
    void warn(Savegame sg, Contract c) {
        FarmlandOwnership field = ownership.get(sg, c.getFarmlandId()).orElseThrow();
        c.setRenewalOffered(true);
        c.setRenewalAmount(Math.max(10, round10(c.getMonthlyAmount()
                * random.uniform(cfg().getRenewalFactorMin(), cfg().getRenewalFactorMax()))));
        c.setPurchasePrice(c.getCharacter().isSellWilling()
                ? round10(field.getReferencePrice() * cfg().getPurchaseFactor()) : null);
        narration.request(sg, NarrationEventType.LEASE_ENDING).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId())
                        .put("endsInDays", Math.max(1, Math.round(GameTime.toDays(c.getEndsAtGameTime() - sg.getCurrentGameTime()))))
                        .put("renewalRent", c.getRenewalAmount()).put("purchasePrice", c.getPurchasePrice()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/contracts?contract=" + c.getId()).submit();
    }

    @EventListener
    @Transactional
    public void onPaymentMissed(ContractEvents.PaymentMissed e) {
        Contract c = contracts.findById(e.contractId()).orElse(null);
        if (c == null || c.getKind() != ContractKind.LEASE || c.getStatus() != ContractStatus.ACTIVE) {
            return;
        }
        Savegame sg = c.getSavegame();
        if (e.missedPayments() >= cfg().getCancelAfterMissedPayments()) {
            giveBack(sg, c, ContractStatus.ENDED, "RENT_MISSED");
            return;
        }
        narration.request(sg, NarrationEventType.LEASE_RENT_OVERDUE).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("monthlyRent", c.getMonthlyAmount())
                        .build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
    }

    /**
     * T-03: the mod did not execute a lease instruction. Start transfer failed → the lease is void; purchase booking
     * refused → the lease continues. A failed return only produces the generic notice. Returns true if handled.
     */
    @Transactional
    public boolean onInstructionFailed(Long contractId, InstructionType type, boolean toPlayer, String reason) {
        Contract c = contracts.findById(contractId).orElse(null);
        if (c == null || c.getKind() != ContractKind.LEASE) {
            return false;
        }
        Savegame sg = c.getSavegame();
        if (type == InstructionType.FARMLAND_TRANSFER && toPlayer && c.getStatus() == ContractStatus.ACTIVE) {
            c.setStatus(ContractStatus.ENDED);
            c.setEndReason("TRANSFER_FAILED");
            c.setEndsAtGameTime(sg.getCurrentGameTime());
            ownership.get(sg, c.getFarmlandId()).ifPresent(f -> f.setLeasedToPlayer(false));
            return true;
        }
        if (type == InstructionType.MONEY_TRANSACTION && "FARMLAND_PURCHASE".equals(reason) && "PURCHASED".equals(c.getEndReason())) {
            c.setStatus(ContractStatus.ACTIVE);
            c.setEndReason(null);
            ownership.setOwner(sg, c.getFarmlandId(), OwnerType.CHARACTER, c.getCharacter());
            ownership.get(sg, c.getFarmlandId()).ifPresent(f -> f.setLeasedToPlayer(true));
            return true;
        }
        return false;
    }

    private static long round10(double v) {
        return Math.round(v / 10.0) * 10;
    }
}
