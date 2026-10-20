package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-S (owner decisions 2026-10-15/16): every purchase in the game shop costs 5 trust to the
 * dealer; the mail waits for the names of the statement, several settled purchases get one mail, a purchase without a
 * name is "eine Maschine"; the dealer of an older savegame comes with the first purchase.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DealerShopPurchaseTest {

    @Autowired Fixtures fx;
    @Autowired DealerShopPurchaseService service;
    @Autowired MachineDealerService dealers;
    @Autowired BookingEntryRepository entries;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;

    Savegame sg;
    long seq;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
    }

    private BookingEntry purchase(String match, String names) {
        BookingEntry x = new BookingEntry();
        x.setSavegame(sg);
        x.setSeq(++seq);
        x.setYear(1);
        x.setPeriod(3);
        x.setCategory("SHOP_VEHICLE_BUY");
        x.setAmount(-120000);
        x.setSingle(true);
        x.setVehicleMatch(match);
        x.setVehicleNames(names);
        return entries.save(x);
    }

    private List<TrustEvent> shopEvents(Character dealer) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(dealer).stream()
                .filter(t -> t.getReason() == TrustReason.DEALER_SHOP_PURCHASE).toList();
    }

    private List<NarrationJob> mails() {
        return jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "DEALER_SHOP_PURCHASE");
    }

    @Test
    void aPurchaseWaitsForTheNamesThenCostsFiveTrustAndBringsOneMail() {
        BookingEntry x = purchase(BookingEntry.MATCH_PENDING, null);
        assertThat(service.react(sg)).isZero();
        assertThat(dealers.dealer(sg)).isEmpty();

        x.setVehicleMatch(BookingEntry.MATCH_MATCHED);
        x.setVehicleNames("Fendt 700 Vario");
        assertThat(service.react(sg)).isEqualTo(1);
        Character dealer = dealers.dealer(sg).orElseThrow(); // the dealer comes with the first purchase (H1)
        assertThat(shopEvents(dealer)).extracting(TrustEvent::getDelta).containsExactly(-5.0);
        assertThat(mails()).hasSize(1);
        assertThat(mails().getFirst().getFactsJson()).contains("\"purchase\":\"den Fendt 700 Vario\"");
        // handled once
        assertThat(service.react(sg)).isZero();
        assertThat(mails()).hasSize(1);
    }

    @Test
    void severalPurchasesGetOneMailAndFiveTrustEach() {
        purchase(BookingEntry.MATCH_AMBIGUOUS, "Fendt 700 Vario, Amazone Catros");
        purchase(BookingEntry.MATCH_AMBIGUOUS, "Fendt 700 Vario, Amazone Catros");
        assertThat(service.react(sg)).isEqualTo(2);
        Character dealer = dealers.dealer(sg).orElseThrow();
        assertThat(shopEvents(dealer)).hasSize(2);
        assertThat(mails()).hasSize(1);
        assertThat(mails().getFirst().getFactsJson()).contains("\"machines\":\"Fendt 700 Vario, Amazone Catros\"");
    }

    @Test
    void aPurchaseWithoutANameIsEineMaschine() {
        purchase(BookingEntry.MATCH_NONE, null);
        service.react(sg);
        assertThat(mails().getFirst().getFactsJson()).contains("\"purchase\":\"eine Maschine\"");
        assertThat(shopEvents(dealers.dealer(sg).orElseThrow())).hasSize(1);
    }

    @Test
    void otherBookingsAndHandledPurchasesDoNotCount() {
        BookingEntry old = purchase(BookingEntry.MATCH_MATCHED, "Alter Traktor");
        old.setDealerReacted(true); // stored before the feature (migration V54)
        BookingEntry sale = purchase(BookingEntry.MATCH_MATCHED, "Fendt 312");
        sale.setCategory("SHOP_VEHICLE_SELL");
        assertThat(service.react(sg)).isZero();
        assertThat(mails()).isEmpty();
    }
}
