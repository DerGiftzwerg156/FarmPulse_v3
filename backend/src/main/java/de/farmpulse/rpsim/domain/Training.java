package de.farmpulse.rpsim.domain;

/**
 * Trainings of machine operators (owner decision "Schulungen"): without a training a machine operator drives small and
 * medium tractors and every vehicle no training is mapped to; the FS25 shop categories behind each training are
 * configured in {@code rpsim.formulas.training.categories}. The titles are used in mails, diary and notes.
 */
public enum Training {
    LARGE_TRACTOR("Große Traktoren"),
    COMBINE("Mähdrescher"),
    FORAGE_HARVESTER("Feldhäcksler"),
    SPECIAL_HARVESTER("Spezialernter"),
    TRUCK("LKW"),
    SELF_PROPELLED("Selbstfahrer & Lader");

    private final String title;

    Training(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
