# Contributing

## Branching model

This is a **solo project**. The default policy is **direct commits to `main`**, each commit referencing
the work package (AP) it belongs to. Short-lived feature branches (`feature/<AP-id>-<slug>`, e.g.
`feature/AP-4.2-credit-scoring`) with a pull request against `main` are used when outside contributions
arrive or when a change should be reviewed before landing.

> Note: automated sessions (e.g. Claude Code on the web) work on a session branch
> (`claude/<name>`) and deliver the result through a pull request against `main`. The commit
> discipline below applies unchanged.

## Commit convention

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short description> [AP-x.y]
```

Types: `feat`, `fix`, `test`, `docs`, `chore`, `refactor`, `ci`.
Scopes: `repo`, `mod`, `tools`, `backend`, `frontend`, `docs`, `github`, `release`, ...

Commit and push after **every** completed work package; large packages are committed in intermediate
steps. A work package is only "done" once it is pushed. Broken intermediate states are never pushed:
backend compiles and tests pass, frontend builds, Lua passes `luacheck`.

## Code style

| Language | Rules |
| --- | --- |
| Java (backend) | Java 21, 4-space indent, constructor injection, no field injection. Every formula value lives in `rpsim.formulas.*` configuration, never as a code constant; comment the concept chapter it comes from. |
| TypeScript (frontend) | Angular standalone components, strict TypeScript, 2-space indent, signals for state. No hard-coded German strings in templates – use the i18n service. |
| Lua (mod) | Lua 5.1 (FS25 runtime), 4-space indent, `local` everything, must pass `luacheck` with the repo `.luacheckrc`. Pure logic lives in modules testable without FS25. |
| Docs | Dev docs in English (`docs/dev`, `docs/architecture`), user guide in German (`docs/user-guide`). |

## Language

Code, comments, commit messages and developer docs are English. UI texts and the user guide are German.

## Open questions

Decisions not covered by the concept documents go to [`QUESTIONS.md`](QUESTIONS.md) before being
implemented. Open technical points that only affect the implementation are marked in code with
`TODO(offene-frage)` and listed in [`docs/dev/offene-technische-punkte.md`](docs/dev/offene-technische-punkte.md).
