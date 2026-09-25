package de.farmpulse.rpsim.onboarding;

import java.util.ArrayList;
import java.util.List;

import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.VillageRelation;

/**
 * Story hooks derived from the structured backstory building blocks (never from the free text). The AI later narrates
 * them in character; this catalogue only fixes key, title, premise and the speaking role.
 */
public final class StoryHookCatalog {

    private StoryHookCatalog() {
    }

    public record Hook(String key, String title, String premise, CharacterRole role) {
    }

    public static List<Hook> candidates(FarmOrigin origin, VillageRelation relation, boolean legacyLoan) {
        List<Hook> l = new ArrayList<>();
        switch (origin == null ? FarmOrigin.BOUGHT_FRESH_START : origin) {
            case INHERITED -> {
                l.add(new Hook("INHERITANCE_LETTER", "Ein Brief aus dem Nachlass",
                        "Im Nachlass taucht ein alter Brief auf, der eine Absprache mit einem Nachbarn erwähnt.",
                        CharacterRole.NEIGHBOR_FARMER));
                l.add(new Hook("OLD_TRACTOR", "Der alte Traktor", "Ein Dorfbewohner erinnert sich an den Traktor des Vorbesitzers.",
                        CharacterRole.VILLAGER));
            }
            case BOUGHT_FRESH_START -> l.add(new Hook("FORMER_OWNER", "Der Vorbesitzer meldet sich",
                    "Der frühere Besitzer des Hofs erkundigt sich, wie es mit dem Betrieb weitergeht.", CharacterRole.VILLAGER));
            case RETURNED_HOME -> l.add(new Hook("SCHOOL_FRIEND", "Ein alter Bekannter",
                    "Eine Bekanntschaft aus Schulzeiten freut sich über die Rückkehr und hat Neuigkeiten.",
                    CharacterRole.NEIGHBOR_FARMER));
            case LEASE_TAKEN_OVER -> l.add(new Hook("LEASE_HANDOVER", "Übergabe der Pacht",
                    "Die Genossenschaft möchte die Übergabe der übernommenen Pacht besprechen.", CharacterRole.COOPERATIVE));
        }
        switch (relation == null ? VillageRelation.UNKNOWN : relation) {
            case STRAINED -> l.add(new Hook("OLD_FEUD", "Eine alte Rechnung",
                    "Im Dorf erinnert man sich an einen alten Streit, der noch nicht vergessen ist.", CharacterRole.VILLAGER));
            case CONNECTED -> l.add(new Hook("REGULARS_TABLE", "Einladung zum Stammtisch",
                    "Der Stammtisch möchte den Hof offiziell im Dorf willkommen heißen.", CharacterRole.VILLAGER));
            case UNKNOWN -> l.add(new Hook("CURIOUS_NEIGHBORS", "Neugierige Nachbarn",
                    "Die Nachbarn wollen wissen, wer den Hof jetzt bewirtschaftet.", CharacterRole.NEIGHBOR_FARMER));
        }
        if (legacyLoan) {
            l.add(new Hook("LEGACY_LOAN_TALK", "Gespräch über die Altlast",
                    "Die Bank bittet um ein Gespräch zum bestehenden Kredit aus der Vorgeschichte.", CharacterRole.BANK_ADVISOR));
        }
        return l;
    }
}
