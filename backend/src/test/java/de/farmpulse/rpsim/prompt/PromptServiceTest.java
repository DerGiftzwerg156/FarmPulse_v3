package de.farmpulse.rpsim.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.GamePrompt;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PromptKind;
import de.farmpulse.rpsim.domain.PromptStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.CreditApplicationRepository;
import de.farmpulse.rpsim.repository.GamePromptRepository;
import de.farmpulse.rpsim.repository.NoticeRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-F: questions in the game for open decisions and their answers. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class PromptServiceTest {

    @Autowired Fixtures fx;
    @Autowired PromptService prompts;
    @Autowired GamePromptRepository promptRepo;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired CommunicationService communications;
    @Autowired CallService calls;
    @Autowired InsuranceService insurance;
    @Autowired ContractRepository contracts;
    @Autowired ServiceCaseRepository cases;
    @Autowired CreditApplicationRepository applications;
    @Autowired NoticeRepository notices;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
        fx.snapshot(sg, 100_000);
    }

    @AfterEach
    void reset() {
        props.getBridge().setIngamePrompts(true);
    }

    private Communication ring() {
        Communication c = communications.create(new CommunicationService.Draft(sg, bank, Channel.CALL,
                CommunicationInitiator.CHARACTER, "Ihr Kreditantrag", "Hallo?", CommunicationCategory.CREDIT, "REPLY",
                null, null, null, null, false, null));
        c.setRingDeadlineGameTime(sg.getCurrentGameTime() + GameTime.hours(1));
        return c;
    }

    private List<OutboxInstruction> promptInstructions() {
        return outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING).stream()
                .filter(o -> o.getType() == InstructionType.PROMPT).toList();
    }

    private GamePrompt only() {
        List<GamePrompt> all = promptRepo.findBySavegameOrderByIdDesc(sg);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private void answer(GamePrompt p, String answer) {
        prompts.onResponses(new BridgeEvents.PlayerResponsesRead(sg.getId(), List.of(
                new BridgeDtos.PlayerAnswer("rsp_" + p.getPromptId(), p.getPromptId(), answer, sg.getCurrentGameTime()))));
        prompts.processAnswers();
    }

    @Test
    void aRingingCallIsAskedOnceWithTheGamesButtonsExplained() {
        Communication call = ring();
        prompts.sync(sg);
        prompts.sync(sg);
        List<OutboxInstruction> ins = promptInstructions();
        assertThat(ins).hasSize(1);
        JsonNode p = json.readTree(ins.get(0).getPayloadJson());
        assertThat(p.path("title").asString()).isEqualTo("Anruf von Frau Berger");
        assertThat(p.path("text").asString()).contains("Frau Berger ruft an (Ihr Kreditantrag)");
        assertThat(p.path("yesLabel").asString()).isEqualTo("Annehmen");
        assertThat(p.path("noLabel").asString()).isEqualTo("Ablehnen");
        assertThat(p.path("expiresGameTime").asLong()).isEqualTo(call.getRingDeadlineGameTime());
        GamePrompt prompt = only();
        assertThat(prompt.getKind()).isEqualTo(PromptKind.CALL);
        assertThat(prompt.getInstructionId()).isEqualTo(ins.get(0).getInstructionId());
    }

    @Test
    void yesAcceptsTheCallWithTheSameServiceAndTheGameIsTold() {
        Communication call = ring();
        prompts.sync(sg);
        GamePrompt p = only();
        answer(p, "YES");
        assertThat(call.getCallStatus()).isEqualTo(CallStatus.ACCEPTED);
        assertThat(p.getStatus()).isEqualTo(PromptStatus.ANSWERED);
        assertThat(p.getResult()).isEqualTo(PromptService.DONE);
        assertThat(outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING))
                .anyMatch(o -> o.getType() == InstructionType.NOTIFICATION && o.getPayloadJson().contains("im Browser bereit"));
        // the same answer again (file not yet acknowledged) changes nothing
        answer(p, "NO");
        assertThat(call.getCallStatus()).isEqualTo(CallStatus.ACCEPTED);
    }

    @Test
    void decidedInTheBrowserTheQuestionIsWithdrawnAndALateAnswerIgnored() {
        Communication call = ring();
        prompts.sync(sg);
        GamePrompt p = only();
        calls.decline(sg, call.getId());
        prompts.sync(sg);
        assertThat(p.getStatus()).isEqualTo(PromptStatus.WITHDRAWN);
        answer(p, "YES");
        assertThat(call.getCallStatus()).isEqualTo(CallStatus.DECLINED);
        assertThat(p.getAnswer()).isNull();
    }

    @Test
    void onlyEnabledOccasionsAreAskedDefaultOnlyCalls() {
        Contract offer = insurance.offer(sg, "BASIC");
        prompts.sync(sg);
        assertThat(promptRepo.findBySavegameOrderByIdDesc(sg)).isEmpty();
        assertThat(prompts.enabledKinds(sg)).containsExactly(PromptKind.CALL);
        prompts.setEnabledKinds(sg, EnumSet.of(PromptKind.CALL, PromptKind.CONTRACT_OFFER));
        prompts.sync(sg);
        GamePrompt p = only();
        assertThat(p.getKind()).isEqualTo(PromptKind.CONTRACT_OFFER);
        assertThat(p.getText()).contains("Basis").contains("im Monat").contains("Selbstbehalt");
        answer(p, "YES");
        assertThat(offer.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        // switched off: open questions of that occasion are withdrawn
        Communication call = ring();
        prompts.sync(sg);
        prompts.setEnabledKinds(sg, Set.of(PromptKind.CONTRACT_OFFER));
        prompts.sync(sg);
        assertThat(promptRepo.findBySavegameAndPromptKey(sg, "CALL:" + call.getId()).orElseThrow().getStatus())
                .isEqualTo(PromptStatus.WITHDRAWN);
    }

    private Contract leaseWithRenewal() {
        prompts.setEnabledKinds(sg, EnumSet.of(PromptKind.CONTRACT_OFFER));
        Character owner = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        Contract lease = new Contract();
        lease.setSavegame(sg);
        lease.setKind(ContractKind.LEASE);
        lease.setStatus(ContractStatus.ACTIVE);
        lease.setCharacter(owner);
        lease.setFarmlandId(13);
        lease.setMonthlyAmount(300);
        lease.setTermMonths(12);
        lease.setEndsAtGameTime(sg.getCurrentGameTime() + GameTime.days(20));
        lease.setRenewalAmount(320L);
        lease.setRenewalOffered(true);
        lease.setCreatedAt(java.time.Instant.now());
        return contracts.save(lease);
    }

    @Test
    void aLeaseRenewalIsConfirmedWithYes() {
        Contract lease = leaseWithRenewal();
        long end = lease.getEndsAtGameTime();
        prompts.sync(sg);
        GamePrompt p = only();
        assertThat(p.getTitle()).isEqualTo("Pacht verlängern");
        assertThat(p.getText()).contains("Feld 13").contains("320 €");
        assertThat(p.getExpiresGameTime()).isEqualTo(end);
        answer(p, "YES");
        assertThat(lease.getMonthlyAmount()).isEqualTo(320);
        assertThat(lease.getEndsAtGameTime()).isGreaterThan(end);
    }

    @Test
    void noToTheRenewalLeavesItOpenInTheBrowserAndIsNotAskedAgain() {
        Contract lease = leaseWithRenewal();
        prompts.sync(sg);
        GamePrompt p = only();
        answer(p, "NO");
        assertThat(p.getResult()).isEqualTo(PromptService.NOTHING);
        assertThat(lease.getRenewalAmount()).isEqualTo(320L);
        prompts.sync(sg);
        assertThat(promptRepo.findBySavegameOrderByIdDesc(sg)).hasSize(1);
    }

    @Test
    void aRefusedActionComesBackToTheGame() {
        prompts.setEnabledKinds(sg, EnumSet.of(PromptKind.TAX_BILL));
        ServiceCase bill = new ServiceCase();
        bill.setSavegame(sg);
        bill.setKind(CaseKind.TAX_BILL);
        bill.setStatus(CaseStatus.AWAITING_PLAYER);
        bill.setCharacter(fx.character(sg, CharacterRole.TAX_OFFICE, CharacterCategory.MANDATORY, "Herr Kramer"));
        bill.setReference("ASSESSMENT");
        bill.setQuantity(1);
        bill.setTitle("Steuerbescheid Jahr 1");
        bill.setOfferAmount(500_000L);
        bill.setCostAmount(0L);
        bill.setGameTime(sg.getCurrentGameTime());
        bill.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(14));
        bill.setCreatedAt(java.time.Instant.now());
        cases.save(bill);
        prompts.sync(sg);
        GamePrompt p = only();
        assertThat(p.getText()).isEqualTo("Steuerbescheid Jahr 1: 500.000 € zahlen.");
        answer(p, "YES");
        assertThat(bill.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(p.getResult()).contains("Kontostand");
        assertThat(outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING))
                .anyMatch(o -> o.getType() == InstructionType.NOTIFICATION && o.getPayloadJson().contains("CRITICAL")
                        && o.getPayloadJson().contains("Kontostand"));
    }

    @Test
    void theCounterOfferOfTheBankIsAcceptedInTheGame() {
        prompts.setEnabledKinds(sg, EnumSet.of(PromptKind.CREDIT_COUNTER));
        CreditApplication a = new CreditApplication();
        a.setSavegame(sg);
        a.setAmount(50_000);
        a.setPurpose("Traktor");
        a.setTermMonths(24);
        a.setSubmittedAtGameTime(sg.getCurrentGameTime());
        a.setDecisionVisibleAtGameTime(sg.getCurrentGameTime());
        a.setDecision(CreditDecision.COUNTER_OFFER);
        a.setOfferedAmount(40_000L);
        a.setOfferedTermMonths(24);
        a.setOfferedInterestRate(0.065);
        a.setStatus(CreditApplicationStatus.DECIDED);
        a.setReasonCategory(de.farmpulse.rpsim.domain.CreditReasonCategory.INSUFFICIENT_LIQUIDITY);
        applications.save(a);
        prompts.sync(sg);
        GamePrompt p = only();
        assertThat(p.getText()).isEqualTo("Statt 50.000 € bietet die Bank 40.000 € über 24 Monate zu 6,5 % Zins an.");
        assertThat(p.getExpiresGameTime()).isEqualTo(sg.getCurrentGameTime() + GameTime.hours(48));
        answer(p, "YES");
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.ACCEPTED);
        assertThat(a.getLoanId()).isNotNull();
    }

    @Test
    void afterAReloadWithoutSavingAnOpenQuestionIsSentAgain() {
        ring();
        prompts.sync(sg);
        GamePrompt p = only();
        OutboxInstruction ins = outbox.findByInstructionId(p.getInstructionId()).orElseThrow();
        ins.setStatus(InstructionStatus.APPLIED);
        ins.setAckedAtGameTime(sg.getCurrentGameTime() + 1000);
        prompts.onRewound(new BridgeEvents.Rewound(sg.getId(), sg.getCurrentGameTime() + 5000, sg.getCurrentGameTime()));
        assertThat(ins.getStatus()).isEqualTo(InstructionStatus.PENDING);
    }

    @Test
    void anOlderModThatCannotAskLeavesTheDecisionInTheBrowserWithoutNotice() {
        ring();
        prompts.sync(sg);
        GamePrompt p = only();
        prompts.onAck(new BridgeEvents.InstructionAcked(sg.getId(), p.getInstructionId(), "FAILED", PromptService.RELATED,
                p.getId()));
        assertThat(p.getStatus()).isEqualTo(PromptStatus.NOT_SHOWN);
        assertThat(notices.findAll()).noneMatch(n -> n.getSavegame().getId().equals(sg.getId()));
    }

    @Test
    void theMasterSwitchWithdrawsEveryQuestion() {
        ring();
        prompts.sync(sg);
        props.getBridge().setIngamePrompts(false);
        prompts.sync(sg);
        assertThat(only().getStatus()).isEqualTo(PromptStatus.WITHDRAWN);
    }
}
