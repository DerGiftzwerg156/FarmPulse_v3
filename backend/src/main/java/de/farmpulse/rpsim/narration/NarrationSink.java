package de.farmpulse.rpsim.narration;

import de.farmpulse.rpsim.domain.NarrationJob;

/**
 * Roadmap V3.1 R31-D1 / R31-D2: receiver of a narrated text that is no mail - a newspaper article or a chat message.
 * A {@link NarrationJob} with {@code targetType} is delivered here instead of creating a communication.
 */
public interface NarrationSink {

    /** The {@code targetType} this sink handles. */
    String targetType();

    /** Stores the text (subject = headline, body = text) in the target {@code job.getTargetId()}. */
    void deliver(NarrationJob job, String subject, String body, boolean fallback);
}
