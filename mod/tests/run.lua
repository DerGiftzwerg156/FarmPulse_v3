-- Runs the complete mod test suite: `lua5.1 tests/run.lua` from the mod/ directory.
package.path = "tests/?.lua;" .. package.path
local lu = require("luaunit")
local helpers = require("helpers")
helpers.loadModules()

local suites = {
    "test_json", "test_scaffold", "test_farm_facts", "test_market_context", "test_instructions",
    "test_price_events", "test_storage", "test_farmland_transfer", "test_savegame_guard", "test_robustness",
    "test_persistence",
}
for _, name in ipairs(suites) do
    local ok, suite = pcall(require, name)
    if ok then
        for k, v in pairs(suite) do
            _G[k] = v
        end
    elseif not tostring(suite):find("module '" .. name .. "' not found", 1, true) then
        error(suite)
    end
end
os.exit(lu.LuaUnit.run("-v"))
