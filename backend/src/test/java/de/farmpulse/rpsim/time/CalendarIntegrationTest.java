package de.farmpulse.rpsim.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import de.farmpulse.rpsim.api.SavegameController;
import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** TODO T-08 / T-09: game month = FS25 period from farm_facts.json, detected conflict mods in the header view. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CalendarIntegrationTest {

    static Path dir = TestBridge.newDir();
    static final long DAY = GameTime.MS_PER_DAY;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired LoanService loans;
    @Autowired LoanRepository loanRepo;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired SavegameController header;

    String id;
    Savegame sg;

    @BeforeEach
    void setUp() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(files.base());
        id = "sg_cal_" + System.nanoTime();
        sg = savegames.save(TestData.activeSavegame(id));
    }

    private void facts(long day, int period, int dayInPeriod, int daysPerPeriod) {
        String calendar = ", \"calendar\": { \"period\": %d, \"dayInPeriod\": %d, \"daysPerPeriod\": %d, \"year\": 2, \"monotonicDay\": %d, \"periodName\": \"Oktober\" } }"
                .formatted(period, dayInPeriod, daysPerPeriod, day);
        String json = TestData.farmFacts(id, day * DAY + 1000, 1_000_000);
        json = json.substring(0, json.lastIndexOf('}')) + calendar;
        TestBridge.write(files.farmFacts(), json);
        sync.runCycle();
    }

    private Savegame fresh() {
        return savegames.findById(sg.getId()).orElseThrow();
    }

    @Test
    void installmentsAreDueAtTheStartOfEachFs25PeriodAndFollowADaysPerPeriodChange() {
        facts(39, 8, 1, 3); // period 8 (October) started on day 39, 3 days per period
        Loan loan = loans.create(fresh(), 12_000, 0.0, 12, "Traktor", true, null);
        assertThat(loan.getNextDueGameTime()).isEqualTo(42 * DAY);
        assertThat(header.current().getBody().calendar().periodName()).isEqualTo("Oktober");

        facts(42, 9, 1, 3); // period 9 starts -> first installment
        Loan l = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(l.getPaidInstallments()).isEqualTo(1);
        assertThat(l.getNextDueGameTime()).isEqualTo(45 * DAY);

        facts(43, 9, 2, 5); // the player switches to 5 days per period
        l = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(l.getNextDueGameTime()).isEqualTo(47 * DAY);
        facts(46, 9, 5, 5);
        assertThat(loanRepo.findById(loan.getId()).orElseThrow().getPaidInstallments()).isEqualTo(1);
        facts(47, 10, 1, 5);
        assertThat(loanRepo.findById(loan.getId()).orElseThrow().getPaidInstallments()).isEqualTo(2);
    }

    @Test
    void detectedConflictModsAreShownInTheHeaderContext() {
        TestBridge.write(files.marketContext(), TestData.marketContext(id).replace("\"fillTypes\"",
                "\"detectedMods\": [\"FS25_UsedPlus\"], \"fillTypes\""));
        facts(39, 8, 1, 3);
        assertThat(header.current().getBody().detectedMods()).containsExactly("FS25_UsedPlus");
    }
}
