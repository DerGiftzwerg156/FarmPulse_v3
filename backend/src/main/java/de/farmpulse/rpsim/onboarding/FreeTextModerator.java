package de.farmpulse.rpsim.onboarding;

/**
 * Light content check for the onboarding free-text field only (implemented in the AI adapter layer, AP-5.9).
 * Running player messages deliberately rely on the provider's own safety filters.
 */
public interface FreeTextModerator {

    /** @return true if the text is acceptable; a rejected text is silently treated like an empty field */
    boolean accept(String text);
}
