package de.farmpulse.rpsim.ai;

/**
 * Prompt handed to a provider: the system prompt (identity + guardrails + memory, building blocks 1-2) and the user
 * prompt (binding facts + task + output format, building blocks 3-4). Built exclusively by {@code PromptBuilder}.
 */
public record AiPrompt(String system, String user) {
}
