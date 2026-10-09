package de.farmpulse.rpsim.newspaper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.ChronicleService;
import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.NewspaperArticle;
import de.farmpulse.rpsim.domain.NewspaperIssue;
import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.VillageNews;
import de.farmpulse.rpsim.farmwork.MachineLoanService;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.narration.PromptBuilder;
import de.farmpulse.rpsim.newspaper.VillageNewsService.Section;
import de.farmpulse.rpsim.repository.AnimalDiseaseRepository;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.DirectPaymentApplicationRepository;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.NewspaperArticleRepository;
import de.farmpulse.rpsim.repository.NewspaperIssueRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.VillageNewsRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V3.1 R31-D1 (owner decisions 2026-10-05): the village newspaper "Dorfblatt". At every period start (after all
 * other month handlers) an issue collects the public facts since the last issue, per section:
 * <ul>
 *   <li>Aus dem Dorf: festivals, arrivals and departures, club sponsoring, gossip (news items of the services);</li>
 *   <li>Vom Hof: public actions of the player (sponsoring, farm shop, neighbour help, fines, public payment defaults,
 *   school visits, cooperative), record harvest and the winter service;</li>
 *   <li>Markt: the fill types with the largest change of the best price against the issue window start, open rumours
 *   "ohne Gewähr";</li>
 *   <li>Amtliches: deadlines (area payment, winter service offer), restricted zones, running inspections without names,
 *   fines of the authority (public action {@code AUTHORITY_FINE});</li>
 *   <li>Kleinanzeigen: open offers of neighbours (goods, animals) and the machines they lend.</li>
 * </ul>
 * The facts go to the AI as one article per non-empty section (target {@code NEWSPAPER_ARTICLE}); without AI the
 * template prints them. Private money matters (loans, balance, taxes, amounts paid) never appear.
 */
@Service
public class VillageNewspaperService {

    public static final String RELATED = "NEWSPAPER_ISSUE";

    private final NewspaperIssueRepository issues;
    private final NewspaperArticleRepository articles;
    private final VillageNewsRepository news;
    private final PublicActionEventRepository publicActions;
    private final MarketEventRepository marketEvents;
    private final FactsSnapshotRepository snapshots;
    private final FactsService facts;
    private final DirectPaymentApplicationRepository directPayments;
    private final ContractRepository contracts;
    private final AnimalDiseaseRepository diseases;
    private final ServiceCaseRepository cases;
    private final CharacterRepository characters;
    private final MachineLoanService machineLoans;
    private final SavegameRepository savegames;
    private final NarrationRequestService narration;
    private final FallbackTemplates labels;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final JsonMapper json;

    public VillageNewspaperService(NewspaperIssueRepository issues, NewspaperArticleRepository articles,
                                   VillageNewsRepository news, PublicActionEventRepository publicActions,
                                   MarketEventRepository marketEvents, FactsSnapshotRepository snapshots, FactsService facts,
                                   DirectPaymentApplicationRepository directPayments, ContractRepository contracts,
                                   AnimalDiseaseRepository diseases, ServiceCaseRepository cases,
                                   CharacterRepository characters, MachineLoanService machineLoans,
                                   SavegameRepository savegames, NarrationRequestService narration,
                                   FallbackTemplates labels, GameTime gameTime, RpsimProperties props, JsonMapper json) {
        this.issues = issues;
        this.articles = articles;
        this.news = news;
        this.publicActions = publicActions;
        this.marketEvents = marketEvents;
        this.snapshots = snapshots;
        this.facts = facts;
        this.directPayments = directPayments;
        this.contracts = contracts;
        this.diseases = diseases;
        this.cases = cases;
        this.characters = characters;
        this.machineLoans = machineLoans;
        this.savegames = savegames;
        this.narration = narration;
        this.labels = labels;
        this.gameTime = gameTime;
        this.props = props;
        this.json = json;
    }

    private RpsimProperties.VillageNewspaper cfg() {
        return props.getFormulas().getVillageNewspaper();
    }

    /** Period start: the regular issue, after the other month handlers dropped their news. */
    @EventListener
    @Order(200)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long index = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        if (cfg().isEnabled() && !issues.existsBySavegameAndMonthIndexAndMidMonth(sg, index, false)) {
            publish(sg, false);
        }
    }

    /** The optional extra issue at the middle of the month. */
    @EventListener
    @Order(200)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || !cfg().isMidMonthIssue()) {
            return;
        }
        long now = sg.getCurrentGameTime();
        long index = gameTime.monthIndex(sg, now);
        long middle = gameTime.monthStart(sg, index) + gameTime.msPerMonth(sg) / 2;
        if (now >= middle && !issues.existsBySavegameAndMonthIndexAndMidMonth(sg, index, true)) {
            publish(sg, true);
        }
    }

    /** Builds an issue from the facts since the last one; empty sections are left out, nothing at all: no issue. */
    @Transactional
    public Optional<NewspaperIssue> publish(Savegame sg, boolean midMonth) {
        long now = sg.getCurrentGameTime();
        long index = gameTime.monthIndex(sg, now);
        Optional<NewspaperIssue> last = issues.findFirstBySavegameOrderByIdDesc(sg);
        long from = last.map(NewspaperIssue::getPublishedGameTime)
                .orElse(gameTime.monthStart(sg, index - (midMonth ? 0 : 1)) - 1);
        Map<Section, List<String>> sections = collect(sg, from, now);
        if (sections.values().stream().allMatch(List::isEmpty)) {
            return Optional.empty();
        }
        NewspaperIssue issue = new NewspaperIssue();
        issue.setSavegame(sg);
        issue.setIssueNumber(last.map(i -> i.getIssueNumber() + 1).orElse(1));
        issue.setMonthIndex(index);
        issue.setMidMonth(midMonth);
        issue.setPeriod(gameTime.periodOfYear(sg, now));
        issue.setCropYear(sg.getCalYear());
        issue.setFromGameTime(Math.max(0, from + 1));
        issue.setPublishedGameTime(now);
        issues.save(issue);
        int position = 0;
        for (Map.Entry<Section, List<String>> s : sections.entrySet()) {
            List<String> items = s.getValue();
            if (items.isEmpty()) {
                continue;
            }
            NewspaperArticle a = new NewspaperArticle();
            a.setSavegame(sg);
            a.setIssueId(issue.getId());
            a.setSection(s.getKey().name());
            a.setPosition(position++);
            a.setFactsJson(json.writeValueAsString(items));
            articles.save(a);
            String first = items.getFirst();
            narration.request(sg, NarrationEventType.NEWSPAPER_ARTICLE)
                    .facts(NarrationFacts.builder().put("section", s.getKey().name()).put("sectionTitle", s.getKey().title())
                            .put("issueNumber", issue.getIssueNumber())
                            .put("headline", first.length() > 90 ? first.substring(0, 87) + "…" : first)
                            .put("items", items.stream().map(i -> "– " + i).collect(Collectors.joining("\n")))
                            .put("itemCount", items.size()).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, issue.getId())
                    .target(PromptBuilder.TARGET_NEWSPAPER, a.getId()).submit();
        }
        return Optional.of(issue);
    }

    // ------------------------------------------------------------------------------------------ facts

    Map<Section, List<String>> collect(Savegame sg, long from, long to) {
        Map<Section, List<String>> out = new EnumMap<>(Section.class);
        for (Section s : Section.values()) {
            out.put(s, new ArrayList<>());
        }
        for (VillageNews n : news.findBySavegameAndGameTimeGreaterThanAndGameTimeLessThanEqualOrderByIdAsc(sg, from, to)) {
            out.get(Section.valueOf(n.getSection())).add(n.getText());
        }
        farm(sg, from, to, out);
        market(sg, from, to, out.get(Section.MARKET));
        official(sg, from, to, out.get(Section.OFFICIAL));
        classifieds(sg, out.get(Section.CLASSIFIEDS));
        int max = Math.max(1, cfg().getMaxItemsPerSection());
        out.replaceAll((k, v) -> v.size() <= max ? v : new ArrayList<>(v.subList(v.size() - max, v.size())));
        return out;
    }

    /** Public actions of the player in the window, one line per type (a fine also stands under "Amtliches"). */
    private void farm(Savegame sg, long from, long to, Map<Section, List<String>> out) {
        String farm = ChronicleService.farmName(sg);
        Map<PublicActionType, Integer> counts = new LinkedHashMap<>();
        for (PublicActionEvent e : publicActions.findBySavegameAndGameTimeGreaterThanAndGameTimeLessThanEqualOrderByGameTimeAsc(
                sg, from, to)) {
            counts.merge(e.getType(), 1, Integer::sum);
        }
        counts.forEach((type, n) -> {
            String text = switch (type) {
                case SPONSORING -> farm + " unterstützt die Vereine im Dorf" + times(n) + ".";
                case FARM_SHOP -> "Der Hofladen von " + farm + " hat Bestellungen aus dem Dorf ausgeliefert" + times(n) + ".";
                case NEIGHBOR_HELP -> farm + " hat Nachbarn mit Ware ausgeholfen" + times(n) + ".";
                case PUBLIC_DEFAULT -> "Im Dorf spricht man über offene Rechnungen von " + farm + ".";
                case AUTHORITY_FINE -> farm + " musste ein Bußgeld an das Landwirtschaftsamt zahlen" + times(n) + ".";
                case SCHOOL_VISIT -> "Eine Schulklasse war zu Besuch auf " + farm + ".";
                case COOPERATIVE -> farm + " engagiert sich in der Genossenschaft.";
                case STAMMTISCH_LONER -> "Am Stammtisch fragt man sich, wo die Leute von " + farm + " bleiben.";
                case INVESTOR_NAMED -> "Ein Investor beteiligt sich an " + farm + "."; // Roadmap V3.2 R32-I3 P5
                case INVESTOR_BREACH -> "Zwischen " + farm + " und seinem Investor gibt es Streit.";
                default -> null;
            };
            if (text != null) {
                out.get(Section.FARM).add(text);
            }
        });
        contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.WINTER_SERVICE,
                List.of(ContractStatus.ACTIVE)).stream().findFirst().ifPresent(c -> out.get(Section.FARM)
                .add(farm + " räumt in diesem Winter die Gemeindestraßen."));
    }

    private static String times(int n) {
        return n > 1 ? " (" + n + "-mal)" : "";
    }

    /** Largest changes of the best price per fill type against the window start; rumours of the window. */
    private void market(Savegame sg, long from, long to, List<String> out) {
        Optional<FarmFacts> latest = facts.latest(sg);
        // the prices at the window start: the last export up to it, else the first export inside the window
        Optional<FactsSnapshot> before = snapshots.findFirstBySavegameAndGameTimeLessThanEqualOrderByGameTimeDescIdDesc(sg,
                Math.max(0, from + 1)).or(() -> snapshots.findBySavegameAndGameTimeBetweenOrderByGameTimeAscIdAsc(sg,
                Math.max(0, from + 1), to).stream().findFirst());
        if (latest.isPresent() && before.isPresent()) {
            Map<String, Double> now = FactsService.bestPrices(latest.get());
            Map<String, Double> then = FactsService.bestPrices(facts.parse(before.get()));
            record Change(String fillType, double percent) {
            }
            List<Change> changes = new ArrayList<>();
            now.forEach((ft, price) -> {
                Double old = then.get(ft);
                if (old != null && old > 0 && price != null) {
                    double pct = (price - old) / old * 100;
                    if (Math.round(pct) != 0) {
                        changes.add(new Change(ft, pct));
                    }
                }
            });
            changes.sort(Comparator.comparingDouble((Change c) -> -Math.abs(c.percent())).thenComparing(Change::fillType));
            changes.stream().limit(cfg().getMarketTopCount()).forEach(c -> out.add(labels.label(c.fillType())
                    + (c.percent() > 0 ? " legt zu: +" : " gibt nach: ") + Math.round(c.percent()) + " % beim besten Preis."));
        }
        for (MarketEvent r : marketEvents.findBySavegameAndStatusIn(sg, EnumSet.of(MarketEventStatus.RUMOR_ONLY))) {
            if (r.getAnnouncedAtGameTime() > from && r.getAnnouncedAtGameTime() <= to && r.getFillType() != null) {
                boolean up = r.getPeakMultiplier() != null && r.getPeakMultiplier() >= 1;
                out.add("Gerücht (ohne Gewähr): " + labels.label(r.getFillType()) + " soll bald "
                        + (up ? "teurer" : "billiger") + " werden.");
            }
        }
    }

    private void official(Savegame sg, long from, long to, List<String> out) {
        long now = sg.getCurrentGameTime();
        for (DirectPaymentApplication a : directPayments.findBySavegameOrderByIdDesc(sg)) {
            if (DirectPaymentApplication.OPEN.equals(a.getStatus()) && a.getDeadlineGameTime() > now) {
                out.add("Frist: Der Sammelantrag für die Flächenprämie muss in " + days(a.getDeadlineGameTime() - now)
                        + " beim Landwirtschaftsamt sein.");
            }
        }
        contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.WINTER_SERVICE, List.of(ContractStatus.OFFERED))
                .stream().filter(c -> c.getOfferExpiresAtGameTime() == null || c.getOfferExpiresAtGameTime() > now).findFirst()
                .ifPresent(c -> out.add("Die Gemeinde sucht einen Hof für den Winterdienst"
                        + (c.getOfferExpiresAtGameTime() == null ? "." : " – Rückmeldung binnen "
                        + days(c.getOfferExpiresAtGameTime() - now) + ".")));
        for (AnimalDisease d : diseases.findBySavegameOrderByIdDesc(sg)) {
            if (AnimalDisease.ACTIVE.equals(d.getStatus())) {
                out.add("Sperrzone wegen " + labels.label(d.getDiseaseKey()) + ": kein Handel mit "
                        + d.types().stream().map(labels::label).collect(Collectors.joining(", ")) + ".");
            }
        }
        boolean inspections = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.AUTHORITY_INSPECTION)).stream()
                .anyMatch(c -> c.getStatus() == CaseStatus.AWAITING_PLAYER
                        || (c.getGameTime() > from && c.getGameTime() <= to));
        if (inspections) {
            out.add("Das Amt kontrolliert derzeit Betriebe in der Gegend.");
        }
        String farm = ChronicleService.farmName(sg);
        long fines = publicActions.findBySavegameAndGameTimeGreaterThanAndGameTimeLessThanEqualOrderByGameTimeAsc(sg, from, to)
                .stream().filter(e -> e.getType() == PublicActionType.AUTHORITY_FINE).count();
        if (fines > 0) {
            out.add("Das Landwirtschaftsamt hat gegen " + farm + " ein Bußgeld verhängt" + times((int) fines) + ".");
        }
    }

    private static String days(long ms) {
        long d = Math.max(1, (long) Math.ceil(GameTime.toDays(ms)));
        return d + (d == 1 ? " Tag" : " Tagen");
    }

    /** Open offers of neighbours to the farm and the machines the neighbours lend. */
    private void classifieds(Savegame sg, List<String> out) {
        for (ServiceCase c : cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.GOODS_OFFER, CaseKind.ANIMAL_OFFER))) {
            if (c.getStatus() != CaseStatus.AWAITING_PLAYER || c.getCharacter() == null) {
                continue;
            }
            if (c.getKind() == CaseKind.GOODS_OFFER) {
                out.add(c.getCharacter().getName() + " verkauft " + c.getQuantity() + " l " + labels.label(c.getReference()) + ".");
            } else {
                out.add(c.getCharacter().getName() + " bietet " + c.getQuantity() + " " + labels.label(c.getTitle())
                        + " an (" + labels.label(c.getReference()) + ").");
            }
        }
        for (Character n : characters.findBySavegameAndRoleAndStatus(sg, CharacterRole.NEIGHBOR_FARMER, CharacterStatus.ACTIVE)) {
            List<MachineLoanService.Choice> choices = machineLoans.loanChoices(sg, n.getId());
            if (!choices.isEmpty()) {
                out.add(n.getName() + " verleiht Maschinen, z. B. " + choices.stream().map(MachineLoanService.Choice::name)
                        .limit(2).collect(Collectors.joining(" oder ")) + ".");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ queries

    public List<NewspaperIssue> list(Savegame sg) {
        return issues.findBySavegameOrderByIdDesc(sg);
    }

    public List<NewspaperArticle> articles(NewspaperIssue issue) {
        return articles.findByIssueIdOrderByPositionAsc(issue.getId());
    }
}
