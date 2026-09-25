# Mod test suite

```bash
cd mod
lua5.1 tests/run.lua   # luaunit suite (JSON, exports, import/idempotency, price events, storage,
                       # farmland transfer, savegameId guard, robustness, persistence)
luacheck .             # static analysis, config in mod/.luacheckrc
```

Tests run without FS25: `tests/helpers.lua` loads the pure modules and replaces the file system
(`RPSimFileIO.backend`) and the game (`RPSimGameAdapter`) with in-memory fakes.
