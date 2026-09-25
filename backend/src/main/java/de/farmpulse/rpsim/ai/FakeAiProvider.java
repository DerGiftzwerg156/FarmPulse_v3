package de.farmpulse.rpsim.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Test double: fixed, deterministic answers; records prompts. Used by tests and the E2E runs (provider FAKE). */
@Component
public class FakeAiProvider implements AiProvider {

    private final List<AiPrompt> prompts = new ArrayList<>();
    private volatile boolean failing;

    @Override
    public String id() {
        return "FAKE";
    }

    @Override
    public synchronized AiResult generate(AiPrompt prompt) {
        prompts.add(prompt);
        if (failing) {
            throw new AiProviderException("fake provider configured to fail");
        }
        return new AiResult("[KI] Nachricht", "Dies ist eine simulierte KI-Antwort.");
    }

    public synchronized List<AiPrompt> prompts() {
        return new ArrayList<>(prompts);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public synchronized void reset() {
        prompts.clear();
        failing = false;
    }
}
