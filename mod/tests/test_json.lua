local lu = require("luaunit")
local T = {}
T.TestJson = {}

function T.TestJson:testRoundTripObject()
    local doc = { a = 1, b = "x\"y", c = { 1, 2, 3 }, d = { e = true, f = false }, g = 1.5 }
    local back = RPSimJson.decode(RPSimJson.encode(doc))
    lu.assertEquals(back.a, 1)
    lu.assertEquals(back.b, "x\"y")
    lu.assertEquals(#back.c, 3)
    lu.assertTrue(back.d.e)
    lu.assertFalse(back.d.f)
    lu.assertEquals(back.g, 1.5)
end

function T.TestJson:testKeysAreSortedForDeterministicOutput()
    lu.assertEquals(RPSimJson.encode({ b = 1, a = 2 }), '{"a":2,"b":1}')
end

function T.TestJson:testEmptyArrayMarker()
    lu.assertEquals(RPSimJson.encode({ list = RPSimJson.array({}) }), '{"list":[]}')
end

function T.TestJson:testUnicodeEscape()
    lu.assertEquals(RPSimJson.decode('"M\\u00fchle"'), "Mühle")
end

function T.TestJson:testNegativeAndExponentNumbers()
    lu.assertEquals(RPSimJson.decode("-1800"), -1800)
    lu.assertEquals(RPSimJson.decode("1.5e3"), 1500)
end

function T.TestJson:testTryDecodeNeverRaises()
    local v, err = RPSimJson.tryDecode('{"a": [1, 2')
    lu.assertNil(v)
    lu.assertNotNil(err)
    v, err = RPSimJson.tryDecode("")
    lu.assertNil(v)
    lu.assertNotNil(err)
end

return T
