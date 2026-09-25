package de.farmpulse.rpsim.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageRelation;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.StoryHookRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** DoD AP-4.8: complete onboarding flow over the real file bridge. */
@SpringBootTest
class OnboardingFlowIntegrationTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired OnboardingService onboarding;
    @Autowired StoryHookScheduler storyHooks;
    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired CharacterRepository characters;
    @Autowired EmployeeRepository employees;
    @Autowired LoanRepository loans;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired StoryHookRepository hooks;
    @Autowired DiaryEntryRepository diary;
    @Autowired TrustEventRepository trustEvents;
    @Autowired CreditApplicationService credit;
    @Autowired TransactionTemplate tx;

    @Test
    void completeOnboardingFlow() {
        // (1) web wizard before the FS25 savegame exists
        Savegame draft = onboarding.create(new OnboardingService.Request(FarmOrigin.INHERITED, VillageRelation.STRAINED,
                "Mein Großvater hat den Hof 1962 gegründet.", 150_000, 80_000L, TonePreset.REALISTIC,
                List.of(JobRole.MACHINE_OPERATOR, JobRole.OFFICE_CLERK)));
        assertThat(draft.getStatus()).isEqualTo(SavegameStatus.DRAFT);
        List<OnboardingService.PreviewEntry> preview = tx.execute(s -> onboarding.preview(draft));
        assertThat(preview).extracting(OnboardingService.PreviewEntry::role)
                .contains(CharacterRole.BANK_ADVISOR, CharacterRole.COOPERATIVE, CharacterRole.AUTHORITY);
        assertThat(preview).filteredOn(p -> p.category() == CharacterCategory.EMPLOYEE).hasSize(2);
        assertThat(preview).filteredOn(p -> p.category() == CharacterCategory.DYNAMIC).hasSize(5);
        // villageRelation STRAINED shifts start trust of village characters
        var neighbor = characters.findById(preview.stream().filter(p -> p.category() == CharacterCategory.DYNAMIC)
                .findFirst().orElseThrow().characterId()).orElseThrow();
        assertThat(neighbor.getTrustScore()).isEqualTo(-10);

        // reroll single + all
        Long bankId = preview.stream().filter(p -> p.role() == CharacterRole.BANK_ADVISOR).findFirst().orElseThrow().characterId();
        onboarding.reroll(draft.getId(), bankId);
        List<OnboardingService.PreviewEntry> afterSingle = tx.execute(s -> onboarding.preview(draft));
        assertThat(afterSingle).extracting(OnboardingService.PreviewEntry::characterId).doesNotContain(bankId);
        assertThat(afterSingle).hasSize(preview.size());
        onboarding.reroll(draft.getId(), null);
        List<OnboardingService.PreviewEntry> afterAll = tx.execute(s -> onboarding.preview(draft));
        assertThat(afterAll).hasSize(preview.size());
        assertThat(afterAll).extracting(OnboardingService.PreviewEntry::characterId)
                .doesNotContainAnyElementsOf(afterSingle.stream().map(OnboardingService.PreviewEntry::characterId).toList());
        assertThat(afterAll).filteredOn(p -> p.category() == CharacterCategory.EMPLOYEE)
                .extracting(OnboardingService.PreviewEntry::jobRole)
                .containsExactlyInAnyOrder(JobRole.MACHINE_OPERATOR, JobRole.OFFICE_CLERK);

        // (2)+(3) player loads the savegame in FS25, the mod reports its id (FS start capital 500 000)
        String id = "map_erlengrund_onb_" + System.nanoTime();
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, 5 * GameTime.MS_PER_DAY, 500_000));
        TestBridge.write(files.marketContext(), TestData.marketContext(id));
        sync.runCycle();
        // (4) unlinked ids are listed with map name
        assertThat(onboarding.unlinkedSavegames()).anyMatch(d -> d.savegameId().equals(id) && "Erlengrund".equals(d.mapName()));
        assertThatThrownBy(() -> onboarding.confirm(draft.getId(), "never_reported"))
                .isInstanceOf(BusinessRuleException.class);

        // (5) confirm & link
        onboarding.confirm(draft.getId(), id);
        Savegame sg = savegames.findByBridgeSavegameId(id).orElseThrow();
        assertThat(sg.getStatus()).isEqualTo(SavegameStatus.ACTIVE);
        tx.executeWithoutResult(s -> {
            Savegame s2 = savegames.findById(sg.getId()).orElseThrow();
            List<Employee> staff = employees.findBySavegameOrderByIdAsc(s2);
            assertThat(staff).hasSize(2).allMatch(e -> e.getPayFairness() == 70 && e.getSkill() >= 30);
            List<Loan> ls = loans.findBySavegameOrderByIdAsc(s2);
            assertThat(ls).singleElement().satisfies(l -> {
                assertThat(l.isLegacy()).isTrue();
                assertThat(l.getRemainingAmount()).isEqualTo(80_000);
            });
            assertThat(outbox.findBySavegameOrderByIdAsc(s2)).noneMatch(o -> o.getPayloadJson().contains("CREDIT_DISBURSEMENT"));
            assertThat(hooks.findBySavegameOrderByScheduledGameTimeAsc(s2)).hasSizeBetween(1, 2)
                    .allMatch(h -> h.getScheduledGameTime() >= s2.getCurrentGameTime() + GameTime.days(3));
            assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(s2).get(0).getText()).contains("geerbt")
                    .contains("Großvater");
            assertThat(jobs.findBySavegameOrderByIdAsc(s2)).extracting(j -> j.getEventType()).contains("ONBOARDING_WELCOME");
        });

        // first snapshot of the linked savegame -> STARTING_CAPITAL_ADJUSTMENT = 150 000 - 500 000
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, 5 * GameTime.MS_PER_DAY + 1000, 500_000));
        sync.runCycle();
        tx.executeWithoutResult(s -> {
            Savegame s2 = savegames.findById(sg.getId()).orElseThrow();
            List<OutboxInstruction> adj = outbox.findBySavegameOrderByIdAsc(s2).stream()
                    .filter(o -> o.getPayloadJson().contains("STARTING_CAPITAL_ADJUSTMENT")).toList();
            assertThat(adj).singleElement().satisfies(o -> assertThat(o.getPayloadJson()).contains("-350000"));
        });
        // a second snapshot does not adjust again
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, 5 * GameTime.MS_PER_DAY + 2000, 490_000));
        sync.runCycle();
        tx.executeWithoutResult(s -> assertThat(outbox.findBySavegameOrderByIdAsc(savegames.findById(sg.getId()).orElseThrow())
                .stream().filter(o -> o.getPayloadJson().contains("STARTING_CAPITAL_ADJUSTMENT"))).hasSize(1));

        // delayed credit visibility over snapshots
        CreditApplication app = tx.execute(s -> credit.submit(savegames.findById(sg.getId()).orElseThrow(), 20_000,
                "Saatgut", 24));
        long visible = app.getDecisionVisibleAtGameTime();
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, visible - 1, 490_000));
        sync.runCycle();
        tx.executeWithoutResult(s -> assertThat(credit.get(savegames.findById(sg.getId()).orElseThrow(), app.getId())
                .getStatus()).isEqualTo(CreditApplicationStatus.PROCESSING));
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, visible, 490_000));
        sync.runCycle();
        tx.executeWithoutResult(s -> assertThat(credit.get(savegames.findById(sg.getId()).orElseThrow(), app.getId())
                .getStatus()).isNotEqualTo(CreditApplicationStatus.PROCESSING));

        // story hooks fire staggered once their time has come
        long lastHook = tx.execute(s -> hooks.findBySavegameOrderByScheduledGameTimeAsc(savegames.findById(sg.getId())
                .orElseThrow()).getLast().getScheduledGameTime());
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, lastHook, 490_000));
        sync.runCycle();
        tx.executeWithoutResult(s -> assertThat(hooks.findBySavegameOrderByScheduledGameTimeAsc(
                savegames.findById(sg.getId()).orElseThrow())).allMatch(h -> h.isFired()));
    }

    @Test
    void emptyFreeTextStillProducesStandardCastAndProfilesAreNotTemplates() {
        Savegame a = onboarding.create(new OnboardingService.Request(null, null, "", 0, null, null, List.of()));
        Savegame b = onboarding.create(new OnboardingService.Request(null, null, null, 0, null, null, List.of()));
        assertThat(a.getId()).isNotEqualTo(b.getId());
        List<OnboardingService.PreviewEntry> cast = tx.execute(s -> onboarding.preview(a));
        assertThat(cast).hasSize(8);
        assertThat(a.getVillageRelation()).isEqualTo(VillageRelation.UNKNOWN);
        assertThat(a.getTonePreset()).isEqualTo(TonePreset.REALISTIC);
        // UNKNOWN = neutral default without trust offset
        tx.executeWithoutResult(s -> characters.findBySavegameOrderByIdAsc(savegames.findById(a.getId()).orElseThrow())
                .forEach(c -> assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c)).isEmpty()));
    }

    @Test
    void invalidStartValuesAreRejected() {
        assertThatThrownBy(() -> onboarding.create(new OnboardingService.Request(null, null, null, -1, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class);
    }
}
