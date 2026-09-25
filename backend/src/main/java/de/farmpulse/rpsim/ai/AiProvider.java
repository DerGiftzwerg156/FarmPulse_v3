package de.farmpulse.rpsim.ai;

/**
 * Pluggable AI provider (technical concept "KI-Adapter"). Provider, key and model come from local configuration,
 * never from code. Implementations: OpenAI, Anthropic, Gemini, Ollama (+ FAKE test double, NONE = always fallback).
 */
public interface AiProvider {

    /** Configuration id (OPENAI, ANTHROPIC, GEMINI, OLLAMA, FAKE, NONE). */
    String id();

    /** @throws AiProviderException on any failure; the caller falls back to a template */
    AiResult generate(AiPrompt prompt);
}
