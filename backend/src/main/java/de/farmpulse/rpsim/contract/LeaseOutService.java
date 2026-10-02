package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.CollateralService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.family.FamilyService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-L1: the player leases an own field out to a neighbour (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>Form: term 1–3 FS25 years and a desired rent per ha and month; guide value = field price × share / 12 / ha.
 *       Up to three active neighbours with enough capital answer with a first bid; the player demands in up to three
 *       rounds (negotiation engine, kind {@code LEASE_OFFER}).</li>
 *   <li>Start: {@code FARMLAND_TRANSFER FROM_PLAYER}. The field has no owner in the game (the base game farms it), in
 *       the tool the player stays the owner ({@link FarmlandOwnership#isLeasedFromPlayer()}). Rent as
 *       {@code LEASE_INCOME} from the next month on (ContractBillingService).</li>
 *   <li>One month before the end the tenant offers a renewal; without it the field comes back
 *       ({@code FARMLAND_TRANSFER TO_PLAYER}). Fallback: the return waits for an empty or harvested field, at most one
 *       month. Taking the field back in the game menu ends the lease at once.</li>
 * </ul>
 */
@Service
public class LeaseOutService {

    /** Related type of the transfers (the rent uses ContractBillingService.RELATED). */
    public static final String RELATED = "LEASE_OUT";

    /** Reclaimed in the game menu (published by FarmlandOwnershipService.reconcile). */
    public record Reclaimed(Long savegameId, int farmlandId) {
    }

    /** Guide value and form limits of an own field. */
    public record Quote(int farmlandId, double hectares, long guideRate, int termYearsMin, int termYearsMax) {
    }

    private final ContractRepository contracts;
    private final NegotiationRepository negotiationRepo;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final ContractBillingService billing;
    private final FarmlandOwnershipService ownership;
    private final NegotiationEngine negotiations;
    private final CollateralService collateral;
    private final LeaseOutPhase phase;
    private final FamilyService family;
    private final OutboxService outbox;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public LeaseOutService(ContractRepository contracts, NegotiationRepository negotiationRepo, SavegameRepository savegames,
                           CharacterRepository characters, ContractBillingService billing,
                           FarmlandOwnershipService ownership, NegotiationEngine negotiations, CollateralService collateral,
                           LeaseOutPhase phase, FamilyService family, OutboxService outbox, CharacterLookup lookup,
                           TrustScoreService trust, NarrationRequestService narration, DiaryService diary,
                           RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.negotiationRepo = negotiationRepo;
        this.savegames = savegames;
        this.characters = characters;
        this.billing = billing;
        this.ownership = ownership;
        this.negotiations = negotiations;
        this.collateral = collateral;
        this.phase = phase;
        this.family = family;
        this.outbox = outbox;
        this.lookup = lookup;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.LeaseOut cfg() {
        return props.getFormulas().getLeaseOut();
    }

    /** Guide rent per ha and month: field price × annual share / 12 / ha (at least 1 €). */
    public static long guideRate(long referencePrice, double hectares, RpsimProperties.LeaseOut cfg) {
        if (hectares <= 0) {
            return 1;
        }
        return Math.max(1, Math.round(referencePrice * cfg.getAnnualRentShare() / 12.0 / hectares));
    }

    /** Monthly rent of the whole field. */
    public static long monthlyRent(long ratePerHa, double hectares) {
        return Math.max(1, Math.round(ratePerHa * hectares));
    }

    public List<Contract> list(Savegame sg) {
        return contracts.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == ContractKind.LEASE_OUT).toList();
    }

    public Optional<Contract> active(Savegame sg, int farmlandId) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.LEASE_OUT, List.of(ContractStatus.ACTIVE))
                .stream().filter(c -> c.getFarmlandId() != null && c.getFarmlandId() == farmlandId).findFirst();
    }

    public Quote quote(Savegame sg, int farmlandId) {
        FarmlandOwnership field = ownField(sg, farmlandId);
        return new Quote(farmlandId, field.getHectares(), guideRate(field.getReferencePrice(), field.getHectares(), cfg()),
                cfg().getTermYearsMin(), cfg().getTermYearsMax());
    }

    private FarmlandOwnership ownField(Savegame sg, int farmlandId) {
        FarmlandOwnership field = ownership.get(sg, farmlandId).orElseThrow(() -> new NotFoundException("farmland " + farmlandId));
        if (field.getOwnerType() != OwnerType.PLAYER || field.isLeasedToPlayer()) {
            throw new BusinessRuleException("NOT_PLAYER_FIELD", "Nur eigene Felder können verpachtet werden.");
        }
        return field;
    }

    // ------------------------------------------------------------------------------------------ offer

    /** Flurkarte "Verpachten": up to three neighbours answer with a first bid per ha and month. */
    @Transactional
    public List<Negotiation> offer(Savegame sg, int farmlandId, int termYears, long desiredRate) {
        FarmlandOwnership field = ownField(sg, farmlandId);
        if (!field.isTradeable()) {
            throw new BusinessRuleException("FIELD_NOT_TRADEABLE", "Diese Fläche kann im Spiel nicht gehandelt werden.");
        }
        if (field.isLeasedFromPlayer() || active(sg, farmlandId).isPresent()) {
            throw new BusinessRuleException("FIELD_LEASED_OUT", "Feld " + farmlandId + " ist bereits verpachtet.");
        }
        if (negotiations.isBlocked(sg, AssetType.FARMLAND, String.valueOf(farmlandId))) {
            throw new BusinessRuleException("FIELD_IN_NEGOTIATION", "Für dieses Feld läuft bereits eine Versteigerung oder Verhandlung.");
        }
        if (termYears < cfg().getTermYearsMin() || termYears > cfg().getTermYearsMax()) {
            throw new BusinessRuleException("INVALID_TERM", "Die Laufzeit muss zwischen " + cfg().getTermYearsMin() + " und "
                    + cfg().getTermYearsMax() + " Jahren liegen.");
        }
        if (desiredRate <= 0) {
            throw new BusinessRuleException("INVALID_RENT", "Die Wunschpacht muss positiv sein.");
        }
        collateral.requireLeasable(sg, farmlandId); // K1: a Grundschuld needs the bank's consent
        phase.requireBare(sg, farmlandId); // fallback: only an empty or harvested field
        long guide = guideRate(field.getReferencePrice(), field.getHectares(), cfg());
        double totalRent = desiredRate * field.getHectares() * 12.0 * termYears;
        List<Character> interested = characters.findBySavegameAndRoleAndStatus(sg, CharacterRole.NEIGHBOR_FARMER,
                        CharacterStatus.ACTIVE).stream()
                .filter(c -> c.getVirtualWealth() >= totalRent)
                .sorted(Comparator.comparing(Character::getId))
                .limit(cfg().getMaxInterested())
                .toList();
        String group = "lease_" + UUID.randomUUID().toString().substring(0, 8);
        long closes = sg.getCurrentGameTime() + GameTime.days(cfg().getOfferValidDays());
        List<Negotiation> out = new ArrayList<>();
        for (Character tenant : interested) {
            Negotiation n = negotiations.openLease(sg, field, tenant, guide, desiredRate, termYears * 12, group, closes);
            long limit = negotiations.leaseMaxAccept(n);
            long raw = Math.round(desiredRate * RandomSource.seeded(tenant.getGenerationSeed() ^ n.getId())
                    .uniform(cfg().getFirstBidMin(), cfg().getFirstBidMax()));
            long first = Math.max(1, Math.min(raw, limit));
            negotiations.counterpartOffer(n, first);
            narration.request(sg, NarrationEventType.LEASE_OUT_BID).from(tenant)
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("hectares", field.getHectares())
                            .put("termYears", termYears).put("desiredRate", desiredRate).put("bidRate", first)
                            .put("monthlyRent", monthlyRent(first, field.getHectares())).build())
                    .category(CommunicationCategory.NEGOTIATION).related(NegotiationEngine.RELATED, n.getId())
                    .formLink("/farmland?negotiation=" + n.getId()).submit();
            out.add(n);
        }
        if (interested.isEmpty()) {
            narration.request(sg, NarrationEventType.LEASE_OUT_NO_INTEREST)
                    .from(lookup.firstActive(sg, CharacterRole.LAND_AGENT, CharacterRole.COOPERATIVE).orElse(null))
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("desiredRate", desiredRate)
                            .put("termYears", termYears).build())
                    .category(CommunicationCategory.NEGOTIATION).submit();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ start

    /** Agreement in the negotiation: contract, transfer to "no owner" in the game, mail and diary. */
    @EventListener
    @Transactional
    public void onAgreed(NegotiationEngine.LeaseAgreed e) {
        Negotiation n = negotiationRepo.findById(e.negotiationId()).orElseThrow();
        if (n.getKind() != NegotiationKind.LEASE_OFFER) {
            return;
        }
        Savegame sg = n.getSavegame();
        int farmlandId = Integer.parseInt(n.getAssetId());
        FarmlandOwnership field = ownership.get(sg, farmlandId).orElseThrow();
        Character tenant = n.getCounterpartCharacter();
        long rate = n.getFinalPrice();
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.LEASE_OUT);
        c.setCharacter(tenant);
        c.setFarmlandId(farmlandId);
        c.setTermMonths(n.getLeaseTermMonths());
        c.setMonthlyAmount(monthlyRent(rate, field.getHectares()));
        c.setCreatedAt(Instant.now());
        billing.activate(sg, c);
        field.setLeasedFromPlayer(true);
        outbox.farmlandTransfer(sg, farmlandId, false, "Verpachtung Feld " + farmlandId, new Related(RELATED, c.getId()));
        narration.request(sg, NarrationEventType.LEASE_OUT_STARTED).from(tenant)
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("rate", rate)
                        .put("monthlyRent", c.getMonthlyAmount()).put("termMonths", c.getTermMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/farmland?contract=" + c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Feld " + farmlandId + " verpachtet", "An " + tenant.getName() + ", "
                + c.getTermMonths() + " Monate, " + c.getMonthlyAmount() + " € pro Monat (" + rate + " € je ha).",
                ContractBillingService.RELATED, c.getId());
        if (Integer.valueOf(farmlandId).equals(sg.getFamilyFieldId())) {
            family.familyFieldLeased(sg, farmlandId);
        }
    }

    // ------------------------------------------------------------------------------------------ end of term

    Contract own(Savegame sg, Long id) {
        return contracts.findById(id)
                .filter(c -> c.getSavegame().getId().equals(sg.getId()) && c.getKind() == ContractKind.LEASE_OUT)
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    /** Renewal at the rent the tenant offered, for another term (until the end of the term). */
    @Transactional
    public Contract renew(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.ACTIVE || c.getRenewalAmount() == null
                || (c.getEndsAtGameTime() != null && sg.getCurrentGameTime() >= c.getEndsAtGameTime())) {
            throw new BusinessRuleException("NO_RENEWAL_OFFER", "Es liegt kein Verlängerungsangebot vor.");
        }
        c.setMonthlyAmount(c.getRenewalAmount());
        c.setEndsAtGameTime(gameTime.addMonths(sg, c.getEndsAtGameTime(), c.getTermMonths()));
        c.setRenewalAmount(null);
        c.setRenewalOffered(false);
        narration.request(sg, NarrationEventType.LEASE_OUT_RENEWED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).put("monthlyRent", c.getMonthlyAmount())
                        .put("termMonths", c.getTermMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Verpachtung verlängert", "Feld " + c.getFarmlandId() + ": weitere " + c.getTermMonths()
                + " Monate an " + c.getCharacter().getName() + ", " + c.getMonthlyAmount() + " € pro Monat.",
                ContractBillingService.RELATED, c.getId());
        return c;
    }

    @EventListener
    @Order(74)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        check(savegames.findById(e.savegameId()).orElseThrow());
    }

    @Transactional
    public void check(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Contract c : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.LEASE_OUT,
                List.of(ContractStatus.ACTIVE))) {
            if (c.getEndsAtGameTime() == null) {
                continue;
            }
            if (now >= c.getEndsAtGameTime()) {
                // fallback: the return waits for an empty or harvested field, at most return-delay-max-months
                boolean waitedEnough = now >= gameTime.addMonths(sg, c.getEndsAtGameTime(), cfg().getReturnDelayMaxMonths());
                if (phase.returnable(sg, c.getFarmlandId()) || waitedEnough) {
                    giveBack(sg, c, ContractStatus.ENDED, "TERM_ENDED");
                }
            } else if (!c.isRenewalOffered()
                    && now >= gameTime.addMonths(sg, c.getEndsAtGameTime(), -cfg().getWarningMonths())) {
                offerRenewal(sg, c);
            }
        }
    }

    /** One month before the end: the tenant offers a renewal for the same term at a new rent. */
    void offerRenewal(Savegame sg, Contract c) {
        c.setRenewalOffered(true);
        c.setRenewalAmount(Math.max(1, Math.round(c.getMonthlyAmount()
                * random.uniform(cfg().getRenewalFactorMin(), cfg().getRenewalFactorMax()))));
        narration.request(sg, NarrationEventType.LEASE_OUT_ENDING).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId())
                        .put("endsInDays", Math.max(1, Math.round(GameTime.toDays(c.getEndsAtGameTime() - sg.getCurrentGameTime()))))
                        .put("renewalRent", c.getRenewalAmount()).put("termMonths", c.getTermMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/farmland?contract=" + c.getId()).submit();
    }

    private void giveBack(Savegame sg, Contract c, ContractStatus status, String reason) {
        c.setStatus(status);
        c.setEndReason(reason);
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        c.setRenewalAmount(null);
        ownership.get(sg, c.getFarmlandId()).ifPresent(f -> f.setLeasedFromPlayer(false));
        outbox.farmlandTransfer(sg, c.getFarmlandId(), true, "Pachtende Feld " + c.getFarmlandId(),
                new Related(RELATED, c.getId()));
        narration.request(sg, NarrationEventType.LEASE_OUT_ENDED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Verpachtung beendet", "Feld " + c.getFarmlandId() + " gehört wieder zum Hof ("
                + c.getCharacter().getName() + " hat es zurückgegeben).", ContractBillingService.RELATED, c.getId());
    }

    // ------------------------------------------------------------------------------------------ game menu

    /** The player bought the leased-out field in the game menu: the lease ends at once, the tenant is annoyed. */
    @EventListener
    @Transactional
    public void onReclaimed(Reclaimed e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Contract c = active(sg, e.farmlandId()).orElse(null);
        if (c == null) {
            return;
        }
        c.setStatus(ContractStatus.CANCELLED);
        c.setEndReason("RECLAIMED_IN_MENU");
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        c.setRenewalAmount(null);
        trust.recordEvent(c.getCharacter(), cfg().getReclaimTrustDelta(), TrustReason.LEASE_OUT_RECLAIMED,
                "Feld " + c.getFarmlandId() + " im Spielmenü zurückgeholt");
        narration.request(sg, NarrationEventType.LEASE_OUT_RECLAIMED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", c.getFarmlandId()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Verpachtung abgebrochen", "Feld " + c.getFarmlandId()
                + " im Spielmenü zurückgekauft – " + c.getCharacter().getName() + " musste die Pacht vorzeitig aufgeben.",
                ContractBillingService.RELATED, c.getId());
    }

    /** T-03: the mod did not give the field away at the start - the lease is void. Returns true if handled. */
    @Transactional
    public boolean onInstructionFailed(Long contractId, InstructionType type, boolean toPlayer) {
        Contract c = contracts.findById(contractId).orElse(null);
        if (c == null || c.getKind() != ContractKind.LEASE_OUT) {
            return false;
        }
        if (type == InstructionType.FARMLAND_TRANSFER && !toPlayer && c.getStatus() == ContractStatus.ACTIVE) {
            Savegame sg = c.getSavegame();
            c.setStatus(ContractStatus.ENDED);
            c.setEndReason("TRANSFER_FAILED");
            c.setEndsAtGameTime(sg.getCurrentGameTime());
            ownership.get(sg, c.getFarmlandId()).ifPresent(f -> f.setLeasedFromPlayer(false));
            diary.addAuto(sg, "CONTRACT", "Verpachtung geplatzt", "Das Spiel konnte Feld " + c.getFarmlandId()
                    + " nicht übergeben. Das Feld bleibt beim Hof.", ContractBillingService.RELATED, c.getId());
            return true;
        }
        return false;
    }
}
