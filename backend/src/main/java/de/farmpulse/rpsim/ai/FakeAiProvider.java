package de.farmpulse.rpsim.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/** Test double: fixed, deterministic answers; records prompts. Used by tests and the E2E runs (provider FAKE). */
@Component
public class FakeAiProvider implements AiProvider {

    private final List<AiPrompt> prompts = new ArrayList<>();
    private volatile boolean failing;
    /** Runs on every call before the answer, outside the lock (tests: block like a slow provider, inspect the thread). */
    private volatile Consumer<AiPrompt> onCall = p -> { };

    @Override
    public String id() {
        return "FAKE";
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        synchronized (this) {
            prompts.add(prompt);
        }
        onCall.accept(prompt);
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

    public void setOnCall(Consumer<AiPrompt> onCall) {
        this.onCall = onCall;
    }

    public synchronized void reset() {
        prompts.clear();
        failing = false;
        onCall = p -> { };
    }
}
