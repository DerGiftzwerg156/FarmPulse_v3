package de.farmpulse.rpsim.common;

/** A request that violates a fact-layer rule (e.g. "one field, one negotiation"). Mapped to HTTP 409. */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
