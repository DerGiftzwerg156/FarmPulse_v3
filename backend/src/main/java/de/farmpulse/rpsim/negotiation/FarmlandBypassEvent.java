package de.farmpulse.rpsim.negotiation;

import de.farmpulse.rpsim.domain.OwnerType;

/**
 * Roadmap V2 R2-D2: a field changed hands in the FS25 field menu outside the tool. {@code purchase} = the player bought
 * it (from {@code previousOwnerType} / {@code previousOwnerId}), otherwise sold it; {@code gamePrice} = the price of the
 * farmland in the game (market context).
 */
public record FarmlandBypassEvent(Long savegameId, int farmlandId, boolean purchase, OwnerType previousOwnerType,
                                  Long previousOwnerId, long gamePrice) {
}
