package de.farmpulse.rpsim.credit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CollateralStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanCollateral;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandBypassEvent;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.LoanCollateralRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-K1: own fields as loan collateral (Grundschuld), owner decisions in QUESTIONS.md.
 * <ul>
 *   <li>Own field = owned by the player in the tool, not leased in; its price comes from
 *   {@code farm_facts.assets.farmland[].price}, collateral value = price x loan-to-value.</li>
 *   <li>A field is tied while it is requested or proposed in an open credit application or pledged for a loan.</li>
 *   <li>Sale in the tool only with the bank's consent: the proceeds repay the collateral value (capped at the remaining
 *   debt) in the sale batch, without prepayment fee; the pledge is released.</li>
 *   <li>Sale in the game menu: trust loss and a claim of the same repayment (case in the Bank app); unpaid after the
 *   deadline it counts as a missed installment and blocks new credits until paid.</li>
 * </ul>
 */
@Service
public class CollateralService {

    public static final String RELATED = "COLLATERAL_CLAIM";
    /** Marker in ServiceCase.resolution: the claim is overdue (stays payable). */
    public static final String OVERDUE = "OVERDUE";
    static final Set<CollateralStatus> TIED = Set.of(CollateralStatus.REQUESTED, CollateralStatus.PROPOSED,
            CollateralStatus.PLEDGED);

    private final LoanCollateralRepository collaterals;
    private final LoanRepository loanRepository;
    private final LoanService loans;
    private final FarmlandOwnershipService ownership;
    private final FactsService facts;
    private final CreditConfigResolver configs;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;

    public CollateralService(LoanCollateralRepository collaterals, LoanRepository loanRepository, LoanService loans,
                             FarmlandOwnershipService ownership, FactsService facts, CreditConfigResolver configs,
                             ServiceCaseRepository cases, SavegameRepository savegames, CharacterLookup lookup,
                             TrustScoreService trust, NarrationRequestService narration, DiaryService diary) {
        this.collaterals = collaterals;
        this.loanRepository = loanRepository;
        this.loans = loans;
        this.ownership = ownership;
        this.facts = facts;
        this.configs = configs;
        this.cases = cases;
        this.savegames = savegames;
        this.lookup = lookup;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
    }

    private RpsimProperties.Collateral cfg(Savegame sg) {
        return configs.forSavegame(sg).getCollateral();
    }

    /** An own field with its price and collateral value. */
    public record FieldOption(int farmlandId, double hectares, long price, long collateralValue) {
    }

    /** Own fields with a price that are not tied to an application or a loan, largest collateral value first. */
    public List<FieldOption> eligible(Savegame sg) {
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.assets() == null || f.assets().farmland() == null) {
            return List.of();
        }
        Set<Integer> tied = collaterals.findBySavegameAndStatusInOrderByIdAsc(sg, TIED).stream()
                .map(LoanCollateral::getFarmlandId).collect(Collectors.toSet());
        Map<Integer, FarmlandOwnership> owned = ownership.list(sg).stream()
                .filter(o -> o.getOwnerType() == OwnerType.PLAYER && !o.isLeasedToPlayer())
                .collect(Collectors.toMap(FarmlandOwnership::getFarmlandId, o -> o, (a, b) -> a));
        double ltv = cfg(sg).getLoanToValue();
        List<FieldOption> out = new ArrayList<>();
        for (BridgeDtos.OwnedFarmland fl : f.assets().farmland()) {
            if (fl.farmlandId() == null || fl.price() == null || fl.price() <= 0 || tied.contains(fl.farmlandId())
                    || !owned.containsKey(fl.farmlandId())) {
                continue;
            }
            long price = Math.round(fl.price());
            out.add(new FieldOption(fl.farmlandId(), fl.hectares() == null ? 0 : fl.hectares(), price, Math.round(price * ltv)));
        }
        // R3-L1: leased-out fields have no owner in the game but stay the player's and stay selectable (owner decision)
        for (FarmlandOwnership o : owned.values()) {
            if (o.isLeasedFromPlayer() && o.getReferencePrice() > 0 && !tied.contains(o.getFarmlandId())) {
                out.add(new FieldOption(o.getFarmlandId(), o.getHectares(), o.getReferencePrice(),
                        Math.round(o.getReferencePrice() * ltv)));
            }
        }
        out.sort(Comparator.comparingLong(FieldOption::collateralValue).reversed().thenComparingInt(FieldOption::farmlandId));
        return out;
    }

    /** The application or loan a field is tied to (requested, proposed or pledged). */
    public Optional<LoanCollateral> tied(Savegame sg, int farmlandId) {
        return collaterals.findBySavegameAndFarmlandIdAndStatusIn(sg, farmlandId, TIED).stream().findFirst();
    }

    /** Fields chosen in the credit form: validated and tied to the application (REQUESTED). */
    @Transactional
    public List<LoanCollateral> request(Savegame sg, CreditApplication a, Collection<Integer> farmlandIds) {
        if (farmlandIds == null || farmlandIds.isEmpty()) {
            return List.of();
        }
        Map<Integer, FieldOption> options = eligible(sg).stream()
                .collect(Collectors.toMap(FieldOption::farmlandId, o -> o));
        List<LoanCollateral> out = new ArrayList<>();
        for (Integer id : new LinkedHashSet<>(farmlandIds)) {
            FieldOption o = id == null ? null : options.get(id);
            if (o == null) {
                throw new BusinessRuleException("COLLATERAL_NOT_AVAILABLE", "Feld " + id
                        + " kann nicht als Sicherheit dienen (kein eigenes Feld oder schon belastet).");
            }
            out.add(create(sg, a, o, CollateralStatus.REQUESTED));
        }
        return out;
    }

    /**
     * Counter offer "mit Grundschuld": unpledged own fields beyond the chosen ones, largest first, until
     * {@code needed} collateral value is reached. Nothing is tied when all fields together are not enough.
     */
    @Transactional
    public List<LoanCollateral> propose(Savegame sg, CreditApplication a, long needed) {
        List<FieldOption> picked = new ArrayList<>();
        long sum = 0;
        for (FieldOption o : eligible(sg)) {
            if (sum >= needed) {
                break;
            }
            picked.add(o);
            sum += o.collateralValue();
        }
        if (sum < needed) {
            return List.of();
        }
        return picked.stream().map(o -> create(sg, a, o, CollateralStatus.PROPOSED)).toList();
    }

    private LoanCollateral create(Savegame sg, CreditApplication a, FieldOption o, CollateralStatus status) {
        LoanCollateral c = new LoanCollateral();
        c.setSavegame(sg);
        c.setApplication(a);
        c.setFarmlandId(o.farmlandId());
        c.setCollateralValue(o.collateralValue());
        c.setStatus(status);
        return collaterals.save(c);
    }

    /** The loan was granted: the requested and proposed fields carry its Grundschuld. */
    @Transactional
    public List<LoanCollateral> pledge(Savegame sg, CreditApplication a, Loan loan) {
        List<LoanCollateral> out = new ArrayList<>();
        for (LoanCollateral c : collaterals.findByApplicationOrderByIdAsc(a)) {
            if (c.getStatus() == CollateralStatus.REQUESTED || c.getStatus() == CollateralStatus.PROPOSED) {
                c.setStatus(CollateralStatus.PLEDGED);
                c.setLoan(loan);
                c.setPledgedGameTime(sg.getCurrentGameTime());
                out.add(c);
            }
        }
        if (!out.isEmpty()) {
            diary.addAuto(sg, "CREDIT", "Grundschuld eingetragen", "Für den Kredit \"" + loan.getPurpose()
                    + "\" ist Feld " + fields(out) + " mit einer Grundschuld belastet (Beleihungswert "
                    + LoanService.euro(out.stream().mapToLong(LoanCollateral::getCollateralValue).sum()) + ").",
                    LoanService.RELATED, loan.getId());
        }
        return out;
    }

    /** The application was rejected or the counter offer declined: its fields are free again. */
    @Transactional
    public void drop(Savegame sg, CreditApplication a) {
        for (LoanCollateral c : collaterals.findByApplicationOrderByIdAsc(a)) {
            if (c.getStatus() == CollateralStatus.REQUESTED || c.getStatus() == CollateralStatus.PROPOSED) {
                c.setStatus(CollateralStatus.RELEASED);
                c.setReleasedGameTime(sg.getCurrentGameTime());
            }
        }
    }

    public List<LoanCollateral> ofApplication(CreditApplication a) {
        return collaterals.findByApplicationOrderByIdAsc(a);
    }

    public List<LoanCollateral> ofLoan(Loan l) {
        return collaterals.findByLoanOrderByIdAsc(l);
    }

    public List<LoanCollateral> pledged(Savegame sg) {
        return collaterals.findBySavegameAndStatusInOrderByIdAsc(sg, List.of(CollateralStatus.PLEDGED));
    }

    // ------------------------------------------------------------------------------------------ sale in the tool

    /** Called before a sale offer of an own field: tied fields need the bank's consent first. */
    public void requireSellable(Savegame sg, int farmlandId) {
        LoanCollateral c = tied(sg, farmlandId).orElse(null);
        if (c == null) {
            return;
        }
        if (c.getStatus() != CollateralStatus.PLEDGED) {
            throw new BusinessRuleException("FIELD_IN_CREDIT_APPLICATION",
                    "Feld " + farmlandId + " ist für einen offenen Kreditantrag als Sicherheit vorgesehen.");
        }
        if (!c.isSaleConsent()) {
            throw new BusinessRuleException("FIELD_PLEDGED", "Auf Feld " + farmlandId
                    + " liegt eine Grundschuld. Bitte zuerst in der App „Bank“ die Zustimmung zum Verkauf einholen.");
        }
    }

    /** Roadmap V3 R3-L1: leasing out a pledged field needs the bank's consent; one tied to an application cannot. */
    public void requireLeasable(Savegame sg, int farmlandId) {
        LoanCollateral c = tied(sg, farmlandId).orElse(null);
        if (c == null) {
            return;
        }
        if (c.getStatus() != CollateralStatus.PLEDGED) {
            throw new BusinessRuleException("FIELD_IN_CREDIT_APPLICATION",
                    "Feld " + farmlandId + " ist für einen offenen Kreditantrag als Sicherheit vorgesehen.");
        }
        if (!c.isLeaseConsent()) {
            throw new BusinessRuleException("FIELD_PLEDGED_LEASE", "Auf Feld " + farmlandId
                    + " liegt eine Grundschuld. Bitte zuerst in der App „Bank“ die Zustimmung zur Verpachtung einholen.");
        }
    }

    /** Roadmap V3 R3-L1: the bank agrees by mail to lease out a pledged field (owner decision); the Grundschuld stays. */
    @Transactional
    public LoanCollateral requestLeaseConsent(Savegame sg, int farmlandId) {
        LoanCollateral c = tied(sg, farmlandId).filter(x -> x.getStatus() == CollateralStatus.PLEDGED)
                .orElseThrow(() -> new BusinessRuleException("NOT_PLEDGED", "Auf Feld " + farmlandId + " liegt keine Grundschuld."));
        if (c.isLeaseConsent()) {
            return c;
        }
        c.setLeaseConsent(true);
        Loan l = c.getLoan();
        narration.request(sg, NarrationEventType.COLLATERAL_LEASE_CONSENT).from(lookup.bank(sg).orElse(null))
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("purpose", l.getPurpose()).build())
                .category(CommunicationCategory.CREDIT).related(LoanService.RELATED, l.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Zustimmung zur Verpachtung", "Die Bank ist einverstanden, dass Feld " + farmlandId
                + " verpachtet wird. Die Grundschuld bleibt eingetragen.", LoanService.RELATED, l.getId());
        return c;
    }

    /**
     * The player asks the bank to agree to a sale (owner decision): the bank agrees by mail on condition that the
     * proceeds repay the collateral value (capped at the remaining debt).
     */
    @Transactional
    public LoanCollateral requestSaleConsent(Savegame sg, int farmlandId) {
        LoanCollateral c = tied(sg, farmlandId).filter(x -> x.getStatus() == CollateralStatus.PLEDGED)
                .orElseThrow(() -> new BusinessRuleException("NOT_PLEDGED", "Auf Feld " + farmlandId + " liegt keine Grundschuld."));
        Loan l = c.getLoan();
        if (c.isSaleConsent()) {
            return c;
        }
        c.setSaleConsent(true);
        long repayment = Math.min(c.getCollateralValue(), l.getRemainingAmount());
        narration.request(sg, NarrationEventType.COLLATERAL_SALE_CONSENT).from(lookup.bank(sg).orElse(null))
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("collateralValue", c.getCollateralValue())
                        .put("repayment", repayment).put("purpose", l.getPurpose()).build())
                .category(CommunicationCategory.CREDIT).related(LoanService.RELATED, l.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Zustimmung zum Feldverkauf", "Die Bank stimmt dem Verkauf von Feld " + farmlandId
                + " zu, wenn der Erlös " + LoanService.euro(repayment) + " des Kredits tilgt.", LoanService.RELATED, l.getId());
        return c;
    }

    /** The tool sale of a field closed: with consent the repayment joins the sale batch and the pledge is released. */
    @Transactional
    public void onSold(Savegame sg, int farmlandId, String batchId) {
        LoanCollateral c = tied(sg, farmlandId).filter(x -> x.getStatus() == CollateralStatus.PLEDGED).orElse(null);
        if (c == null) {
            return;
        }
        String instructionId = loans.collateralRepayment(sg, c.getLoan(), c.getCollateralValue(), batchId,
                "Tilgung aus Verkauf Feld " + farmlandId);
        if (c.getStatus() == CollateralStatus.PLEDGED) { // a full repayment already released it with the loan
            c.setStatus(CollateralStatus.RELEASED);
            c.setReleasedGameTime(sg.getCurrentGameTime());
            c.setReleaseInstructionId(instructionId);
        }
    }

    // ------------------------------------------------------------------------------------------ sale in the game menu

    /** R2-D2 detection: a pledged field was sold in the game menu - the bank always reacts (owner decision). */
    @EventListener
    @Transactional
    public void onFarmlandBypass(FarmlandBypassEvent e) {
        if (e.purchase()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        LoanCollateral c = tied(sg, e.farmlandId()).orElse(null);
        if (c == null) {
            return;
        }
        long now = sg.getCurrentGameTime();
        c.setStatus(CollateralStatus.RELEASED);
        c.setReleasedGameTime(now);
        if (c.getLoan() == null || c.getLoan().getStatus() != LoanStatus.ACTIVE) {
            return; // only requested / proposed for an application, or the loan is no longer running
        }
        Loan l = c.getLoan();
        RpsimProperties.Collateral cfg = cfg(sg);
        Character bank = lookup.bank(sg).orElse(null);
        if (bank != null) {
            trust.recordEvent(bank, cfg.getMenuSaleTrustDelta(), TrustReason.COLLATERAL_SOLD,
                    "Belastetes Feld " + e.farmlandId() + " im Spielmenü verkauft");
        }
        long claim = Math.min(c.getCollateralValue(), l.getRemainingAmount());
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.COLLATERAL_CLAIM);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(bank);
        sc.setFarmlandId(e.farmlandId());
        sc.setOfferAmount(claim);
        sc.setReference(String.valueOf(l.getId()));
        sc.setTitle(l.getPurpose());
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg.getClaimDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.COLLATERAL_CLAIM).from(bank)
                .facts(NarrationFacts.builder().put("farmlandId", e.farmlandId()).put("claim", claim)
                        .put("claimDays", Math.round(cfg.getClaimDays())).put("purpose", l.getPurpose()).build())
                .category(CommunicationCategory.CREDIT).related(RELATED, sc.getId()).formLink("/bank?case=" + sc.getId())
                .submit();
        diary.addAuto(sg, "CREDIT", "Belastetes Feld verkauft", "Feld " + e.farmlandId() + " mit Grundschuld wurde im "
                + "Spielmenü verkauft. Die Bank fordert eine Sondertilgung von " + LoanService.euro(claim) + ".",
                RELATED, sc.getId());
    }

    /** The player pays the claim by button (no prepayment fee). */
    @Transactional
    public ServiceCase payClaim(Savegame sg, Long caseId) {
        ServiceCase sc = openClaim(sg, caseId);
        Loan l = loanRepository.findById(Long.valueOf(sc.getReference())).orElseThrow();
        if (l.getStatus() == LoanStatus.ACTIVE) {
            loans.collateralRepayment(sg, l, sc.getOfferAmount(), null, "Sondertilgung nach Verkauf Feld " + sc.getFarmlandId());
        }
        sc.setStatus(CaseStatus.SETTLED);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        return sc;
    }

    private ServiceCase openClaim(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.COLLATERAL_CLAIM || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Diese Forderung ist bereits erledigt.");
        }
        return sc;
    }

    /** Daily: an unpaid claim after its deadline - missed installment, trust, serious mail; it stays payable. */
    @EventListener
    @Order(79)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() != CaseKind.COLLATERAL_CLAIM || sc.getResolution() != null
                    || sc.getDeadlineGameTime() == null || sc.getDeadlineGameTime() > now) {
                continue;
            }
            Loan l = loanRepository.findById(Long.valueOf(sc.getReference())).orElse(null);
            if (l == null || l.getStatus() != LoanStatus.ACTIVE) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setResolution("LOAN_CLOSED");
                sc.setClosedAtGameTime(now);
                continue;
            }
            sc.setResolution(OVERDUE);
            loans.registerClaimMiss(l);
            Character bank = lookup.bank(sg).orElse(null);
            if (bank != null) {
                trust.recordEvent(bank, cfg(sg).getClaimOverdueTrustDelta(), TrustReason.COLLATERAL_CLAIM_OVERDUE,
                        "Forderung nach Feldverkauf nicht bezahlt");
            }
            narration.request(sg, NarrationEventType.COLLATERAL_CLAIM_OVERDUE).from(bank)
                    .facts(NarrationFacts.builder().put("farmlandId", sc.getFarmlandId()).put("claim", sc.getOfferAmount())
                            .put("purpose", l.getPurpose()).build())
                    .category(CommunicationCategory.CREDIT).related(RELATED, sc.getId()).formLink("/bank?case=" + sc.getId())
                    .submit();
        }
    }

    /** No new credits while a claim after a menu sale is overdue (owner decision). */
    public boolean hasOverdueClaim(Savegame sg) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER).stream()
                .anyMatch(sc -> sc.getKind() == CaseKind.COLLATERAL_CLAIM && OVERDUE.equals(sc.getResolution()));
    }

    public List<ServiceCase> claims(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.COLLATERAL_CLAIM));
    }

    static String fields(List<LoanCollateral> list) {
        return String.join(", ", list.stream().map(c -> String.valueOf(c.getFarmlandId())).toList());
    }
}
