package de.farmpulse.rpsim.common;

/** Small numeric helpers shared by the formula services. */
public final class Formulas {

    private Formulas() {
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Saturating normalisation to 0..100: {@code value / full} scaled to 100 and clamped, so values beyond
     * {@code full} never grow without bound (technical concept: "mit Sättigung, kein linearer Verlauf ins
     * Unendliche").
     */
    public static double saturate(double value, double full) {
        if (full <= 0) {
            return 0;
        }
        return clamp(value / full * 100.0, 0, 100);
    }
}
