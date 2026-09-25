package de.farmpulse.rpsim.tone;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.CreditConfigResolver;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class ToneClassifierTest {

    @Autowired ToneClassifier classifier;
    @Autowired ToneTrustService toneTrust;
    @Autowired TrustScoreService trust;
    @Autowired CreditConfigResolver credit;
    @Autowired RpsimProperties props;
    @Autowired Fixtures fx;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Vielen Dank für Ihre schnelle Hilfe, liebe Grüße!|FRIENDLY",
            "Moin, danke dir, das ist super.|FRIENDLY",
            "Ich habe die Unterlagen gelesen.|NEUTRAL",
            "Wann ist der nächste Termin?|NEUTRAL",
            "Sie sind ein unfähiger Idiot.|RUDE",
            "Das ist eine Frechheit, machen Sie das gefälligst sofort!|RUDE",
            "ICH WILL ENDLICH EINE ANTWORT HABEN|RUDE",
            "Danke, aber das ist lächerlich und eine Frechheit.|RUDE"
    })
    void classifiesExampleSentences(String text, ToneClass expected) {
        assertThat(classifier.classify(text).tone()).isEqualTo(expected);
    }

    @Test
    void emptyOrNullIsNeutral() {
        assertThat(classifier.classify(null).tone()).isEqualTo(ToneClass.NEUTRAL);
        assertThat(classifier.classify("   ").tone()).isEqualTo(ToneClass.NEUTRAL);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Gib mir einen besseren Zins!|true",
            "Können Sie den Zinssatz senken?|true",
            "Erhöh mein Gehalt um 500 Euro.|true",
            "Wie geht es der Familie?|false",
            "Der Kredit war eine große Hilfe.|false"
    })
    void detectsMechanicalWishesWithoutChangingAnything(String text, boolean mechanical) {
        assertThat(classifier.classify(text).mechanicalRequest()).isEqualTo(mechanical);
    }

    @Test
    void toneTrustDeltaIsCapped() {
        props.getFormulas().getTone().setRudeDelta(-50);
        try {
            assertThat(toneTrust.delta(ToneClass.RUDE)).isEqualTo(-2);
            assertThat(toneTrust.delta(ToneClass.FRIENDLY)).isEqualTo(1);
            assertThat(toneTrust.delta(ToneClass.NEUTRAL)).isZero();
        } finally {
            props.getFormulas().getTone().setRudeDelta(-2);
        }
    }

    @Test
    void toneFeedsTrust() {
        Savegame sg = fx.savegame();
        Character c = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Uwe Voss");
        toneTrust.apply(c, ToneClass.FRIENDLY);
        toneTrust.apply(c, ToneClass.RUDE);
        toneTrust.apply(c, ToneClass.NEUTRAL);
        assertThat(trust.getCurrentTrust(c)).isEqualTo(-1);
    }

    @Test
    void harshToneSwitchesOnlyTheBankProfile() {
        var def = credit.forTone(TonePreset.REALISTIC);
        var hard = credit.forTone(TonePreset.HARSH);
        assertThat(credit.forTone(TonePreset.IDYLLIC)).isSameAs(def);
        assertThat(hard.getApproveThreshold()).isEqualTo(85);
        assertThat(hard.getCounterThreshold()).isEqualTo(55);
        assertThat(hard.getTrustCap()).isEqualTo(5);
        assertThat(def.getApproveThreshold()).isEqualTo(75);
        assertThat(def.getCounterThreshold()).isEqualTo(45);
        assertThat(def.getTrustCap()).isEqualTo(8);
        // all other formula sections exist exactly once - no tone variants (deliberate concept decision)
        assertThat(RpsimProperties.Formulas.class.getDeclaredFields()).extracting(f -> f.getName())
                .filteredOn(n -> n.toLowerCase().contains("hard")).containsExactly("creditHard");
    }
}
