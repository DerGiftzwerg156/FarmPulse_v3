# AI providers

The AI only writes texts (see [`two-tier-principle.md`](../architecture/two-tier-principle.md)). Every provider gets
the same four prompt blocks from `PromptBuilder` (character, memory, facts, task) and must answer with a JSON object
`{"subject": "...", "body": "..."}` (`AiOutputParser`). If the call fails `rpsim.ai.max-attempts` times - no key,
HTTP error, timeout, refusal, unparsable output - `AiNarrationService` uses the German fallback template of the
event type instead (`backend/src/main/resources/fallback-templates/de/*.txt`). The game never waits on an AI.

## Where the configuration comes from

Resolution order per value (`AiSettingsService`):

1. `rpsim.ai.local-config-file` (default `./data/local-config/ai-provider.properties`, **git-ignored**) - written
   by the settings page (`PUT /api/settings/ai`): `provider`, `<provider>.model`, `<provider>.apiKey`,
   `<provider>.baseUrl`.
2. Spring configuration: `application-local.yml` next to the backend (**git-ignored**, template
   `backend/application-local.yml.example`), command-line arguments or environment variables
   (`RPSIM_AI_ANTHROPIC_API_KEY` …).
3. Defaults from `application.yml` (never contains a key).

`GET /api/settings/ai` returns only `apiKeySet: true/false` - a stored key is never sent back to the browser.

Protection of the key (technical review 10/2026, Phase 0.4/0.5):

- `PUT /api/settings/ai` is accepted from the gaming PC (loopback) only; a tablet gets `403 LAN_GAME_PC_ONLY` and sees
  the form read-only (`editable: false`).
- A stored key is only ever sent to the host it was entered for: a `baseUrl` with another host and no new key in the
  same request discards the key (an empty `<provider>.apiKey` entry, which also hides a key from
  `application-local.yml`). Enter the key again for the new address.
- The local file is written with owner-only permissions (POSIX `600`, Windows ACL with the owner alone); a file of an
  older version is restricted on start.
Never commit a key: `.gitignore` covers `*.local.yml`, `application-local.yml`, `.env*` and `backend/data/`.

## Providers

| Provider | `rpsim.ai.provider` | Default model | Endpoint / SDK | Needs |
| --- | --- | --- | --- | --- |
| No AI | `NONE` | – | fallback templates only | nothing (default) |
| OpenAI | `OPENAI` | `gpt-4o-mini` | `POST {base-url}/chat/completions`, `Authorization: Bearer` | API key from platform.openai.com |
| Anthropic | `ANTHROPIC` | `claude-opus-5` | official `anthropic-java` SDK (Messages API, beta `server-side-fallback-2026-07-01` with `fallbacks: default`) | API key from console.anthropic.com |
| Google Gemini | `GEMINI` | `gemini-2.5-flash` | `POST {base-url}/models/{model}:generateContent`, header `x-goog-api-key`, JSON output | API key from Google AI Studio |
| Ollama (local) | `OLLAMA` | `llama3.1` | `POST {base-url}/api/chat` with `format: json`, no key | `ollama serve` + `ollama pull <model>` |
| Fake (tests) | `FAKE` | – | fixed text, records prompts | only for tests / E2E (hidden in the UI) |

### OpenAI

```yaml
rpsim.ai:
  provider: OPENAI
  openai: { api-key: sk-..., model: gpt-4o-mini }
```

Any OpenAI-compatible server (e.g. a proxy) can be used via `openai.base-url`.

### Anthropic

```yaml
rpsim.ai:
  provider: ANTHROPIC
  anthropic: { api-key: sk-ant-..., model: claude-opus-5 }
```

The request enables server-side fallbacks, so a refusal of the primary model is retried by the API itself; a
remaining refusal counts as failure (template). The default model is listed in `QUESTIONS.md` for confirmation.

### Google Gemini

```yaml
rpsim.ai:
  provider: GEMINI
  gemini: { api-key: AIza..., model: gemini-2.5-flash }
```

**Note (AP-5.4):** Google renames and retires the free-tier models regularly (`…-flash`, `…-flash-lite`,
preview suffixes). If calls fail with 404 / "model not found", set a current model name from Google AI Studio in
the settings page or `rpsim.ai.gemini.model` - no code change needed. The default could not be verified against the
live documentation at implementation time (see `QUESTIONS.md`).

### Ollama

```yaml
rpsim.ai:
  provider: OLLAMA
  ollama: { base-url: http://localhost:11434, model: llama3.1 }
```

Small local models sometimes break the JSON format; the parser then falls back to the template for that message.

## Adding a provider

Implement `AiProvider` (`id()`, `generate(AiPrompt)` - read model/key/URL via `AiSettingsService.settings(id)`), register it as a Spring bean (the
`AiProviderRegistry` picks it up), add defaults to `RpsimProperties.Ai` + `application.yml`, a label in the
frontend (`enums.aiProvider` in `de.json`) and a test with `MockAiHttp` like `OpenAiProviderTest`.
