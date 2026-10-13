package de.farmpulse.rpsim.field;

import java.util.Map;

/**
 * Roadmap V4 R4-D1: a premium was booked for fields - the area payment ({@code DIRECT_PAYMENT}) or the rotation premium
 * ({@code ROTATION}) of a harvest year, split per field ({@code perField}: farmland id -> euro). {@code reference} keys
 * the booking so that it is taken over only once.
 */
public record FieldPremiumPaid(Long savegameId, String kind, int year, Map<Integer, Double> perField, String reference) {
}
