package de.farmpulse.rpsim.dealer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.BookingStatementService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-S (owner decisions 2026-10-15/16): every purchase in the game shop (single booking
 * {@code SHOP_VEHICLE_BUY} of the statement, R2-B1) lowers the trust to the dealer by 5 and brings a mail ("Ihr Kauf im
 * Shop"). The dealer waits until the statement has assigned the names of the new machines (or found none); several
 * purchases settled at the same time get one mail, the trust falls per purchase; without a name the mail says "eine
 * Maschine". Purchases stored before the feature count as handled. The dealer of an older savegame comes with the first
 * purchase (RFH-H1). FarmPulse's own deliveries book VEHICLE_NEW_PURCHASE and never count.
 */
@Service
public class DealerShopPurchaseService {

    private final SavegameRepository savegames;
    private final BookingEntryRepository entries;
    private final MachineDealerService dealers;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final RpsimProperties props;

    public DealerShopPurchaseService(SavegameRepository savegames, BookingEntryRepository entries,
                                     MachineDealerService dealers, TrustScoreService trust,
                                     NarrationRequestService narration, RpsimProperties props) {
        this.savegames = savegames;
        this.entries = entries;
        this.dealers = dealers;
        this.trust = trust;
        this.narration = narration;
        this.props = props;
    }

    /** After the statement (order 8) has stored the bookings and assigned the names. */
    @EventListener
    @Order(9)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        savegames.findById(e.savegameId()).ifPresent(this::react);
    }

    /** Reacts to the settled purchases not handled yet; returns how many purchases were handled. */
    @Transactional
    public int react(Savegame sg) {
        List<BookingEntry> settled = entries.findBySavegameAndCategoryAndDealerReactedFalseOrderBySeqAsc(sg,
                BookingStatementService.VEHICLE_BUY).stream()
                .filter(x -> x.getVehicleMatch() != null && !BookingEntry.MATCH_PENDING.equals(x.getVehicleMatch())).toList();
        if (settled.isEmpty()) {
            return 0;
        }
        Character dealer = dealers.open(sg);
        Set<String> names = new LinkedHashSet<>();
        for (BookingEntry x : settled) {
            x.setDealerReacted(true);
            if (x.getVehicleNames() != null && !x.getVehicleNames().isBlank()
                    && !BookingEntry.MATCH_NONE.equals(x.getVehicleMatch())) {
                names.add(x.getVehicleNames());
            }
            trust.recordEvent(dealer, props.getFormulas().getMachineDealer().getShopPurchaseTrustDelta(),
                    TrustReason.DEALER_SHOP_PURCHASE, x.getVehicleNames() == null ? "Kauf im Shop" : x.getVehicleNames());
        }
        String machines = String.join(", ", names);
        narration.request(sg, NarrationEventType.DEALER_SHOP_PURCHASE).from(dealer)
                .facts(NarrationFacts.builder().put("machines", machines)
                        .put("purchase", machines.isEmpty() ? "eine Maschine" : "den " + machines).build())
                .category(CommunicationCategory.NEGOTIATION).formLink("/haendler/katalog").submit();
        return settled.size();
    }
}
