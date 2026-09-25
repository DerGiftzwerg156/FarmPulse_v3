package de.farmpulse.rpsim.ai;

/** Any provider failure (network, timeout, HTTP error, refusal, unparsable output) - leads to the fallback template. */
public class AiProviderException extends RuntimeException {

    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
