package de.farmpulse.rpsim.authority;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.DirectPaymentField;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.DirectPaymentApplicationRepository;
import de.farmpulse.rpsim.repository.DirectPaymentFieldRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B1: area payment application and premium (owner decisions 2026-10-05 in QUESTIONS.md).
 * <ul>
 *   <li>At the start of open-period (March) the authority sends the form of the FS25 year; the deadline is the end of
 *   deadline-period (May). The form lists the own fields (fields leased out are ownerless in the game and not
 *   exported; leased-in fields are) with their current crop; the player confirms or corrects the crop per field
 *   (direct-payment.crops, the fruit types of the own fields or BRACHE = fallow).</li>
 *   <li>Late: late-cut-per-day of the premium per started game day; after late-max-days the application lapses (no
 *   premium).</li>
 *   <li>On-site check: rolled once per application at the first month start of check-periods with check-probability
 *   (switch AREA_CHECK, idyllic factor), announced like an inspection of the authority. The declared crop is compared
 *   with the main crop of the year in the crop history (R2-C1; none = BRACHE): cut = premium of the deviating area x
 *   cut-factor (x cut-factor-repeat when the savegame deviated before), capped at the premium. The rotation rules of
 *   R2-E2 count as requirements: a field with the crop of the year before loses authority.rotation-cut-share of its
 *   premium. Both cuts are scaled by the idyllic factor.</li>
 *   <li>At the start of payment-period (December) the premium (premium-per-ha x declared hectares minus the cuts) is
 *   paid as DIRECT_PAYMENT.</li>
 * </ul>
 */
@Service
public class DirectPaymentService {

    public static final String RELATED = "DIRECT_PAYMENT";
    public static final String RULE = "AREA_PAYMENT";
    public static final String FALLOW = "BRACHE";

    private final SavegameRepository savegames;
    private final DirectPaymentApplicationRepository applications;
    private final DirectPaymentFieldRepository lines;
    private final ServiceCaseRepository cases;
    private final ContractRepository contracts;
    private final FactsService facts;
    private final AuthorityService authority;
    private final BurdeningEvents burden;
    private final ServiceRoleService roles;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public DirectPaymentService(SavegameRepository savegames, DirectPaymentApplicationRepository applications,
                                DirectPaymentFieldRepository lines, ServiceCaseRepository cases,
                                ContractRepository contracts, FactsService facts, AuthorityService authority,
                                BurdeningEvents burden, ServiceRoleService roles, OutboxService outbox,
                                NarrationRequestService narration, TrustScoreService trust, DiaryService diary,
                                RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.applications = applications;
        this.lines = lines;
        this.cases = cases;
        this.contracts = contracts;
        this.facts = facts;
        this.authority = authority;
        this.burden = burden;
        this.roles = roles;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.DirectPayment cfg() {
        return props.getFormulas().getDirectPayment();
    }

    public List<DirectPaymentApplication> list(Savegame sg) {
        return applications.findBySavegameOrderByIdDesc(sg);
    }

    public List<DirectPaymentField> fields(DirectPaymentApplication a) {
        return lines.findByApplicationIdOrderByFarmlandIdAsc(a.getId());
    }

    /** Last game time a late application is still accepted. */
    public long lateLimit(DirectPaymentApplication a) {
        return a.getDeadlineGameTime() + GameTime.days(cfg().getLateMaxDays());
    }

    // ------------------------------------------------------------------------------------------ form

    /** One row of the form: an own field with its current crop as suggestion. */
    public record FormField(int farmlandId, String fieldName, double hectares, String suggestedCrop) {
    }

    /** Own fields of the latest export without fields leased out (R3-L). */
    List<BridgeDtos.Field> ownFields(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null) {
            return List.of();
        }
        Set<Integer> leasedOut = contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.LEASE_OUT,
                List.of(ContractStatus.ACTIVE)).stream().map(c -> c.getFarmlandId()).collect(Collectors.toSet());
        return f.fields().stream().filter(x -> x != null && x.farmlandId() != null && !leasedOut.contains(x.farmlandId()))
                .toList();
    }

    /** Crops offered: direct-payment.crops, the fruit types of the own fields, BRACHE. */
    public List<String> crops(Savegame sg) {
        Set<String> out = new LinkedHashSet<>(cfg().getCrops());
        ownFields(sg).stream().map(BridgeDtos.Field::fruitType).filter(t -> t != null && !t.isBlank()).forEach(out::add);
        out.add(FALLOW);
        return List.copyOf(out);
    }

    public List<FormField> form(Savegame sg) {
        List<String> crops = crops(sg);
        return ownFields(sg).stream().map(x -> new FormField(x.farmlandId(),
                x.name() == null ? String.valueOf(x.farmlandId()) : x.name(), x.hectares() == null ? 0 : x.hectares(),
                x.fruitType() != null && crops.contains(x.fruitType()) ? x.fruitType() : FALLOW)).toList();
    }

    // ------------------------------------------------------------------------------------------ month start

    @EventListener
    @Order(80)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        int period = gameTime.periodOfYear(sg, sg.getCurrentGameTime());
        if (period == cfg().getPaymentPeriod()) {
            for (DirectPaymentApplication a : list(sg)) {
                if (DirectPaymentApplication.SUBMITTED.equals(a.getStatus())) {
                    pay(sg, a);
                }
            }
        }
        if (cfg().getCheckPeriods().contains(period)) {
            for (DirectPaymentApplication a : list(sg)) {
                if (DirectPaymentApplication.SUBMITTED.equals(a.getStatus())) {
                    check(sg, a);
                }
            }
        }
        if (period == cfg().getOpenPeriod()) {
            open(sg);
        }
    }

    /** The authority sends the form of the running FS25 year (once per year, needs the calendar of the mod). */
    Optional<DirectPaymentApplication> open(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = f == null || f.calendar() == null ? null : f.calendar().year();
        if (year == null || list(sg).stream().anyMatch(a -> a.getCropYear() == year)) {
            return Optional.empty();
        }
        long now = sg.getCurrentGameTime();
        int period = gameTime.periodOfYear(sg, now);
        int months = Math.floorMod(cfg().getDeadlinePeriod() - period, GameTime.PERIODS_PER_YEAR) + 1;
        DirectPaymentApplication a = new DirectPaymentApplication();
        a.setSavegame(sg);
        a.setCharacter(roles.ensure(sg, CharacterRole.AUTHORITY));
        a.setCropYear(year);
        a.setStatus(DirectPaymentApplication.OPEN);
        a.setOpenedGameTime(now);
        a.setDeadlineGameTime(gameTime.monthStart(sg, gameTime.monthIndex(sg, now) + months));
        applications.save(a);
        double ha = ownFields(sg).stream().mapToDouble(x -> x.hectares() == null ? 0 : x.hectares()).sum();
        narration.request(sg, NarrationEventType.DIRECT_PAYMENT_OPEN).from(a.getCharacter())
                .facts(NarrationFacts.builder().put("cropYear", year).put("premiumPerHa", Math.round(cfg().getPremiumPerHa()))
                        .put("hectares", round1(ha)).put("deadlineMonth", CalendarText.month(cfg().getDeadlinePeriod()))
                        .put("paymentMonth", CalendarText.month(cfg().getPaymentPeriod())).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, a.getId())
                .formLink("/aemter?directPayment=" + a.getId()).submit();
        return Optional.of(a);
    }

    // ------------------------------------------------------------------------------------------ submission

    public record Declared(int farmlandId, String crop) {
    }

    /** "Antrag stellen": the declared crop per field; late = cut per started game day, after late-max-days refused. */
    @Transactional
    public DirectPaymentApplication submit(Savegame sg, Long id, List<Declared> declared) {
        DirectPaymentApplication a = own(sg, id);
        long now = sg.getCurrentGameTime();
        if (!DirectPaymentApplication.OPEN.equals(a.getStatus())) {
            throw new BusinessRuleException("APPLICATION_CLOSED", "Dieser Sammelantrag ist bereits erledigt.");
        }
        if (now > lateLimit(a)) {
            throw new BusinessRuleException("DEADLINE_MISSED", "Die Frist für den Sammelantrag ist abgelaufen.");
        }
        if (declared == null || declared.isEmpty()) {
            throw new BusinessRuleException("NO_FIELDS", "Bitte mindestens ein Feld angeben.");
        }
        Map<Integer, BridgeDtos.Field> own = ownFields(sg).stream()
                .collect(Collectors.toMap(BridgeDtos.Field::farmlandId, Function.identity(), (x, y) -> x));
        List<String> crops = crops(sg);
        Set<Integer> seen = new HashSet<>();
        List<DirectPaymentField> rows = new ArrayList<>();
        for (Declared d : declared) {
            BridgeDtos.Field field = own.get(d.farmlandId());
            if (field == null) {
                throw new BusinessRuleException("NOT_OWN_FIELD", "Feld " + d.farmlandId() + " gehört nicht zum Hof.");
            }
            if (!seen.add(d.farmlandId())) {
                throw new BusinessRuleException("DUPLICATE_FIELD", "Feld " + d.farmlandId() + " ist doppelt angegeben.");
            }
            if (d.crop() == null || !crops.contains(d.crop())) {
                throw new BusinessRuleException("UNKNOWN_CROP", "Unbekannte Kultur für Feld " + d.farmlandId() + ".");
            }
            DirectPaymentField r = new DirectPaymentField();
            r.setSavegame(sg);
            r.setApplicationId(a.getId());
            r.setFarmlandId(d.farmlandId());
            r.setFieldName(field.name() == null ? String.valueOf(d.farmlandId()) : field.name());
            r.setHectares(field.hectares() == null ? 0 : field.hectares());
            r.setDeclaredCrop(d.crop());
            rows.add(r);
        }
        lines.saveAll(rows);
        a.setStatus(DirectPaymentApplication.SUBMITTED);
        a.setSubmittedGameTime(now);
        a.setLateDays(now > a.getDeadlineGameTime() ? (int) Math.ceil(GameTime.toDays(now - a.getDeadlineGameTime())) : 0);
        double ha = rows.stream().mapToDouble(DirectPaymentField::getHectares).sum();
        diary.addAuto(sg, "OTHER", "Sammelantrag " + a.getCropYear() + " gestellt", rows.size() + " Felder mit "
                + round1(ha) + " ha" + (a.getLateDays() > 0 ? ", " + a.getLateDays() + " Tage verspätet." : "."),
                RELATED, a.getId());
        return a;
    }

    /** Daily: an application not submitted within late-max-days after the deadline lapses; checks are decided. */
    @EventListener
    @Order(80)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (DirectPaymentApplication a : list(sg)) {
            if (DirectPaymentApplication.OPEN.equals(a.getStatus()) && now > lateLimit(a)) {
                a.setStatus(DirectPaymentApplication.LAPSED);
                a.setClosedGameTime(now);
                narration.request(sg, NarrationEventType.DIRECT_PAYMENT_LAPSED).from(a.getCharacter())
                        .facts(NarrationFacts.builder().put("cropYear", a.getCropYear()).build())
                        .category(CommunicationCategory.CONTRACT).related(RELATED, a.getId()).submit();
                diary.addAuto(sg, "OTHER", "Sammelantrag " + a.getCropYear() + " versäumt",
                        "Kein Antrag gestellt – in diesem Jahr gibt es keine Flächenprämie.", RELATED, a.getId());
            } else if (DirectPaymentApplication.CHECK_ANNOUNCED.equals(a.getCheckStatus())) {
                ServiceCase sc = a.getCheckCaseId() == null ? null : cases.findById(a.getCheckCaseId()).orElse(null);
                if (sc == null || (sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() <= now)) {
                    decide(sg, a, sc);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ on-site check

    /** Month start of a check period: roll once, announce when selected and an inspection slot is free. */
    void check(Savegame sg, DirectPaymentApplication a) {
        if (DirectPaymentApplication.CHECK_NONE.equals(a.getCheckStatus())) {
            boolean selected = burden.on(sg, Burden.AREA_CHECK)
                    && random.chance(cfg().getCheckProbability() * burden.factor(sg));
            a.setCheckStatus(selected ? DirectPaymentApplication.CHECK_PENDING : DirectPaymentApplication.CHECK_NOT_SELECTED);
        }
        if (DirectPaymentApplication.CHECK_PENDING.equals(a.getCheckStatus()) && authority.mayAnnounce(sg)) {
            ServiceCase sc = authority.announce(sg, a.getCharacter() != null ? a.getCharacter()
                    : roles.ensure(sg, CharacterRole.AUTHORITY), RULE, String.valueOf(a.getId()), null,
                    "Sammelantrag " + a.getCropYear());
            a.setCheckStatus(DirectPaymentApplication.CHECK_ANNOUNCED);
            a.setCheckCaseId(sc.getId());
        }
    }

    /** Result of an on-site check. */
    public record CheckResult(double deviatingHectares, long deviationCut, long rotationCut, List<String> deviating,
                              List<String> repeated) {
    }

    /** Compares the declared crops with the crop history and sets the cuts (pure apart from the crop lookup). */
    CheckResult evaluate(Savegame sg, DirectPaymentApplication a) {
        List<DirectPaymentField> rows = fields(a);
        double premium = rows.stream().mapToDouble(DirectPaymentField::getHectares).sum() * cfg().getPremiumPerHa();
        boolean before = list(sg).stream().anyMatch(x -> !x.getId().equals(a.getId()) && x.getDeviatingHectares() != null
                && x.getDeviatingHectares() > 0);
        double devHa = 0;
        double rotation = 0;
        List<String> deviating = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (DirectPaymentField r : rows) {
            Optional<String> now = authority.crop(sg, r.getFarmlandId(), a.getCropYear());
            Optional<String> last = authority.crop(sg, r.getFarmlandId(), a.getCropYear() - 1);
            r.setActualCrop(now.orElse(FALLOW));
            if (!r.getDeclaredCrop().equals(r.getActualCrop())) {
                devHa += r.getHectares();
                deviating.add(r.getFieldName());
            }
            r.setRotationRepeat(now.isPresent() && last.isPresent() && now.get().equals(last.get()));
            if (r.isRotationRepeat()) {
                rotation += r.getHectares() * cfg().getPremiumPerHa() * props.getFormulas().getAuthority().getRotationCutShare();
                repeated.add(r.getFieldName());
            }
        }
        double factor = burden.factor(sg);
        long devCut = Math.round(Math.min(premium, devHa * cfg().getPremiumPerHa()
                * (before ? cfg().getCutFactorRepeat() : cfg().getCutFactor())) * factor);
        long rotCut = Math.round(rotation * factor);
        return new CheckResult(devHa, devCut, rotCut, deviating, repeated);
    }

    void decide(Savegame sg, DirectPaymentApplication a, ServiceCase sc) {
        CheckResult r = evaluate(sg, a);
        a.setDeviatingHectares(round2(r.deviatingHectares()));
        a.setDeviationCut(r.deviationCut());
        a.setRotationCut(r.rotationCut());
        a.setCheckStatus(DirectPaymentApplication.CHECK_DONE);
        boolean cut = r.deviationCut() + r.rotationCut() > 0;
        long now = sg.getCurrentGameTime();
        if (sc != null && sc.getStatus() == CaseStatus.IN_PROGRESS) {
            sc.setStatus(CaseStatus.SETTLED);
            sc.setResolution(cut ? "CUT" : "IN_ORDER");
            sc.setCostAmount(r.deviationCut() + r.rotationCut());
            sc.setClosedAtGameTime(now);
        }
        if (cut && a.getCharacter() != null) {
            trust.recordEvent(a.getCharacter(), props.getFormulas().getAuthority().getViolationTrustDelta(),
                    TrustReason.AUTHORITY_VIOLATION, "Vor-Ort-Kontrolle Sammelantrag " + a.getCropYear());
        }
        if (a.getCharacter() != null) {
            narration.request(sg, NarrationEventType.DIRECT_PAYMENT_CHECK_RESULT).from(a.getCharacter())
                    .facts(NarrationFacts.builder().put("cropYear", a.getCropYear()).put("inOrder", !cut)
                            .put("deviatingHectares", round1(r.deviatingHectares()))
                            .put("deviatingFields", String.join(", ", r.deviating()))
                            .put("deviationCut", r.deviationCut()).put("repeatedFields", String.join(", ", r.repeated()))
                            .put("rotationCut", r.rotationCut()).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, a.getId()).submit();
        }
        diary.addAuto(sg, "OTHER", "Vor-Ort-Kontrolle Sammelantrag " + a.getCropYear(), cut
                ? "Kürzung " + (r.deviationCut() + r.rotationCut()) + " € (abweichende Kultur " + round1(r.deviatingHectares())
                + " ha" + (r.repeated().isEmpty() ? "" : ", Fruchtfolge " + String.join(", ", r.repeated())) + ")."
                : "Ohne Beanstandung.", RELATED, a.getId());
    }

    // ------------------------------------------------------------------------------------------ payment

    /** The premium of the declared hectares minus late cut and check cuts, paid as DIRECT_PAYMENT. */
    void pay(Savegame sg, DirectPaymentApplication a) {
        if (DirectPaymentApplication.CHECK_ANNOUNCED.equals(a.getCheckStatus())) {
            decide(sg, a, a.getCheckCaseId() == null ? null : cases.findById(a.getCheckCaseId()).orElse(null));
        } else if (DirectPaymentApplication.CHECK_PENDING.equals(a.getCheckStatus())
                || DirectPaymentApplication.CHECK_NONE.equals(a.getCheckStatus())) {
            a.setCheckStatus(DirectPaymentApplication.CHECK_NOT_SELECTED);
        }
        double ha = fields(a).stream().mapToDouble(DirectPaymentField::getHectares).sum();
        long premium = Math.round(ha * cfg().getPremiumPerHa());
        long late = Math.round(premium * Math.min(1, a.getLateDays() * cfg().getLateCutPerDay()));
        long cuts = (a.getDeviationCut() == null ? 0 : a.getDeviationCut()) + (a.getRotationCut() == null ? 0 : a.getRotationCut());
        long paid = Math.max(0, premium - late - cuts);
        a.setPremium(premium);
        a.setLateCut(late);
        a.setPaidAmount(paid);
        a.setStatus(DirectPaymentApplication.PAID);
        a.setClosedGameTime(sg.getCurrentGameTime());
        if (paid > 0) {
            outbox.money(sg, paid, MoneyReason.DIRECT_PAYMENT, "Flächenprämie " + a.getCropYear(), new Related(RELATED, a.getId()));
        }
        if (a.getCharacter() != null) {
            narration.request(sg, NarrationEventType.DIRECT_PAYMENT_PAID).from(a.getCharacter())
                    .facts(NarrationFacts.builder().put("cropYear", a.getCropYear()).put("hectares", round1(ha))
                            .put("premium", premium).put("lateCut", late).put("cuts", cuts).put("paid", paid).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, a.getId()).submit();
        }
        diary.addAuto(sg, "MARKET", "Flächenprämie " + a.getCropYear(), paid + " € für " + round1(ha) + " ha"
                + (late + cuts > 0 ? " (gekürzt um " + (late + cuts) + " €)." : "."), RELATED, a.getId());
    }

    private DirectPaymentApplication own(Savegame sg, Long id) {
        return applications.findById(id).filter(a -> a.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("direct payment " + id));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
