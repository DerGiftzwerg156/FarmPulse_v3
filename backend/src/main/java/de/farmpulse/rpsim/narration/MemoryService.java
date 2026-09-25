package de.farmpulse.rpsim.narration;

import java.util.List;

import de.farmpulse.rpsim.domain.Character;
import org.springframework.stereotype.Service;

/** Deterministic memory short facts (completed in AP-5.8). */
@Service
public class MemoryService {

    public List<String> shortFacts(Character character) {
        return List.of();
    }
}
