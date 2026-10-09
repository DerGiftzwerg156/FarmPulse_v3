package de.farmpulse.rpsim.common;

/**
 * Roadmap V3 R3-N1 / technical review 10/2026 Phase 0.4: a setting of the installation (home-network switch, PIN, AI
 * provider and key) changed from another device than the gaming PC. Mapped to HTTP 403 {@code LAN_GAME_PC_ONLY}.
 */
public class GamePcOnlyException extends RuntimeException {

    public GamePcOnlyException() {
        super("Nur am Spiele-PC änderbar");
    }
}
