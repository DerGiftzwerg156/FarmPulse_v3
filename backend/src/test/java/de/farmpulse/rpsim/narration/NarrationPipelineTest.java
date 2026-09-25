package de.farmpulse.rpsim.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import de.farmpulse.rpsim.ai.AiSettingsService;
import de.farmpulse.rpsim.ai.FakeAiProvider;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class NarrationPipelineTest {

    @Autowired Fixtures fx;
    @Autowired NarrationRequestService requests;
    @Autowired AiNarrationService narration;
    @Autowired AiSettingsService settings;
    @Autowired FakeAiProvider fake;
    @Autowired FallbackTemplates fallbacks;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
        settings.save("FAKE", null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        fake.reset();
        Files.deleteIfExists(Path.of(props.getAi().getLocalConfigFile()));
    }

    NarrationJob job(NarrationEventType type, Channel channel) {
        return requests.request(sg, type).from(bank).channel(channel)
                .facts(NarrationFacts.builder().put("requestedAmount", 50000).put("purpose", "Traktor")
                        .put("reasonCategory", "INSUFFICIENT_EQUITY").build()).submit();
    }

    @Test
    void workerTurnsJobIntoCommunicationWithAiText() {
        NarrationJob j = job(NarrationEventType.CREDIT_APPROVED, Channel.MAIL);
        Communication c = narration.process(j);
        assertThat(c.getBody()).isEqualTo("Dies ist eine simulierte KI-Antwort.");
        assertThat(c.isUsedFallback()).isFalse();
        assertThat(c.isReadFlag()).isFalse();
        assertThat(j.getStatus()).isEqualTo(NarrationJobStatus.DONE);
        assertThat(fake.prompts()).singleElement().satisfies(p -> assertThat(p.system()).contains(bank.getName()));
    }

    @Test
    void jobWaitsForItsGameTime() {
        NarrationJob j = requests.request(sg, NarrationEventType.REPLY).from(bank).delay(1000).submit();
        assertThat(narration.process(j)).isNull();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + 1000);
        assertThat(narration.process(j)).isNotNull();
    }

    @Test
    void incomingCallStartsRinging() {
        Communication c = narration.process(job(NarrationEventType.MARKET_PRICE_EVENT, Channel.CALL));
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.RINGING);
        assertThat(c.getRingDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime() + 2 * 3_600_000L);
    }

    @ParameterizedTest
    @EnumSource(NarrationEventType.class)
    void providerFailureFallsBackForEveryEventType(NarrationEventType type) {
        fake.setFailing(true);
        NarrationJob j = job(type, Channel.MAIL);
        Communication c = narration.process(j);
        assertThat(c).isNotNull();
        assertThat(c.isUsedFallback()).isTrue();
        assertThat(c.getSubject()).isNotBlank();
        assertThat(c.getBody()).isNotBlank().doesNotContain("{{");
        assertThat(j.getStatus()).isEqualTo(NarrationJobStatus.FALLBACK);
        assertThat(j.getLastError()).contains("fail");
    }

    @Test
    void noProviderConfiguredUsesFallbackWithoutError() {
        settings.save("NONE", null, null, null);
        Communication c = narration.process(job(NarrationEventType.CREDIT_REJECTED, Channel.MAIL));
        assertThat(c.isUsedFallback()).isTrue();
        assertThat(c.getBody()).contains("zu wenig Eigenkapital").contains("50.000");
    }

    @Test
    void absenceNoteAppearsInFallback() {
        var r = fallbacks.render(NarrationEventType.REPLY, java.util.Map.of("absenceNote", true), "Frau Berger");
        assertThat(r.body()).startsWith("(Automatische Abwesenheitsnotiz: Frau Berger");
    }

    @Test
    void fillTypesAppearInGermanInFallbackTexts() {
        var r = fallbacks.render(NarrationEventType.MARKET_PRICE_EVENT,
                java.util.Map.of("fillType", "OAT", "sellPoint", "Mühle Nord", "changePercent", 12, "durationDays", 5,
                        "startsInDays", 0), "Herr Meyer");
        assertThat(r.subject()).isEqualTo("Neuigkeiten vom Markt: Hafer");
        assertThat(r.body()).contains("Preis für Hafer").doesNotContain("OAT");
    }
}
