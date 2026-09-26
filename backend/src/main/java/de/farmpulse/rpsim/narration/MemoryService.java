package de.farmpulse.rpsim.narration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.repository.CreditApplicationRepository;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;

/**
 * Memory over time as deterministic short facts (technical concept "Gedächtnis"): derived from events that are
 * logged anyway (TrustEvent, credit decisions, negotiations, diary moments) - never an AI summary, so characters
 * stay consistent over months without drift. Limited to the most recent N facts per character.
 */
@Service
public class MemoryService {

    private record Fact(long gameTime, long order, String text) {
    }

    private final TrustEventRepository trustEvents;
    private final CreditApplicationRepository applications;
    private final NegotiationRepository negotiations;
    private final DiaryEntryRepository diary;
    private final RpsimProperties props;

    public MemoryService(TrustEventRepository trustEvents, CreditApplicationRepository applications,
                         NegotiationRepository negotiations, DiaryEntryRepository diary, RpsimProperties props) {
        this.trustEvents = trustEvents;
        this.applications = applications;
        this.negotiations = negotiations;
        this.diary = diary;
        this.props = props;
    }

    static String day(long gameTime) {
        return "Spieltag " + GameTime.dayIndex(gameTime);
    }

    static String trustText(TrustEvent e) {
        return switch (e.getReason()) {
            case ON_TIME_PAYMENT -> "Rate pünktlich bezahlt";
            case ON_TIME_PAYMENT_REVERSED -> "Rate konnte nicht abgebucht werden";
            case MISSED_PAYMENT, PAYMENT_ESCALATION -> "Zahlungsverzug beim Kredit";
            case PROMISE_KEPT -> "Zusage eingehalten";
            case PROMISE_BROKEN -> "Zusage gebrochen";
            case CALL_DECLINED -> "Anruf abgelehnt";
            case CALL_MISSED -> "Anruf verpasst";
            case TONE_FRIENDLY -> "freundliche Nachricht erhalten";
            case TONE_RUDE -> "schroffe Nachricht erhalten";
            case NEGOTIATION_DEAL -> "Geschäft per Handschlag abgeschlossen";
            case WILDLIFE_AGREEMENT -> "Wildschaden gütlich geregelt";
            case WILDLIFE_DISPUTE -> "Streit um den Wildschaden";
            case INITIAL -> "erste Begegnung";
            case OTHER -> e.getNote() == null ? "Begegnung" : e.getNote();
        };
    }

    public List<String> shortFacts(Character c) {
        List<Fact> facts = new ArrayList<>();
        for (TrustEvent e : trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c)) {
            facts.add(new Fact(e.getGameTime(), e.getId(), trustText(e)));
        }
        if (c.getRole() == CharacterRole.BANK_ADVISOR) {
            // the fact file belongs to the role: credit decisions are known to every bank advisor
            for (CreditApplication a : applications.findBySavegameOrderByIdDesc(c.getSavegame())) {
                if (a.getStatus() == CreditApplicationStatus.PROCESSING) {
                    continue;
                }
                String decision = switch (a.getDecision()) {
                    case APPROVED -> "genehmigt";
                    case COUNTER_OFFER -> "mit Gegenangebot beantwortet";
                    case REJECTED -> "abgelehnt (" + a.getReasonCategory() + ")";
                };
                facts.add(new Fact(a.getDecisionVisibleAtGameTime(), a.getId(),
                        "Kreditantrag über " + a.getAmount() + " € für \"" + a.getPurpose() + "\" " + decision));
            }
        }
        for (Negotiation n : negotiations.findBySavegameOrderByIdDesc(c.getSavegame())) {
            boolean involved = (n.getCounterpartCharacter() != null && n.getCounterpartCharacter().getId().equals(c.getId()))
                    || (n.getAnnouncingCharacter() != null && n.getAnnouncingCharacter().getId().equals(c.getId()));
            if (involved && n.getStatus() != NegotiationStatus.OPEN && n.getClosedAtGameTime() != null) {
                facts.add(new Fact(n.getClosedAtGameTime(), n.getId(), "Verhandlung über Feld " + n.getAssetId() + ": "
                        + switch (n.getStatus()) {
                            case ACCEPTED -> "Einigung bei " + n.getFinalPrice() + " €";
                            case LOST -> "an einen anderen Bieter gegangen";
                            case WITHDRAWN -> "vom Hof zurückgezogen";
                            default -> "ohne Einigung beendet";
                        }));
            }
        }
        diary.findBySavegameOrderByGameTimeAscIdAsc(c.getSavegame()).stream()
                .filter(d -> "CHARACTER".equals(d.getRelatedEntityType()) && c.getId().equals(d.getRelatedEntityId()))
                .forEach(d -> facts.add(new Fact(d.getGameTime(), d.getId(), d.getTitle())));
        int max = props.getFormulas().getMemory().getMaxFacts();
        return facts.stream()
                .sorted(Comparator.comparingLong(Fact::gameTime).thenComparingLong(Fact::order).reversed())
                .limit(max)
                .sorted(Comparator.comparingLong(Fact::gameTime).thenComparingLong(Fact::order))
                .map(f -> day(f.gameTime()) + ": " + f.text())
                .toList();
    }
}
