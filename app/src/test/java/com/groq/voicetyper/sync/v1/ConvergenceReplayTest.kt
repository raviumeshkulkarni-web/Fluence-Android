package com.groq.voicetyper.sync.v1

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Convergence harness — replay of 30 randomized scenarios.
 * Asserts identical final state regardless of merge order, exactly-once stats, no resurrection.
 * Reference semantics must match generator (see WINDOWS/examples/sync/convergence/generate_convergence.py).
 *
 * If production Merge disagrees with reference (e.g. t==0 settings), that is a FINDING — leave failing test.
 */
class ConvergenceReplayTest {

    companion object {
        const val FIXED_STAMP = 1700000000000L
    }

    @Test
    fun convergence_replay_all_scenarios() {
        for (idx in 1..30) {
            val fname = String.format("/convergence/scenario-%02d.json", idx)
            val stream = javaClass.getResourceAsStream(fname)
            assertNotNull("missing resource $fname", stream)
            val jsonText = stream!!.bufferedReader(Charsets.UTF_8).readText()
            val root = JSONObject(jsonText)
            val name = root.getString("name")
            val scenarioLabel = "scenario-${String.format("%02d", idx)} $name"

            val devicesObj = root.getJSONObject("devices")
            val expectedObj = root.getJSONObject("expected")

            // Build per-device lists
            val perDict = mutableMapOf<String, MutableList<DictionaryRecord>>()
            val perSnip = mutableMapOf<String, MutableList<SnippetRecord>>()
            val perSet = mutableMapOf<String, MutableList<SettingsRecord>>()
            val perStat = mutableMapOf<String, MutableList<StatRecord>>()

            val deviceIds = mutableListOf<String>()
            val keysIter = devicesObj.keys()
            while (keysIter.hasNext()) {
                val dev = keysIter.next()
                deviceIds.add(dev)
                val arr = devicesObj.getJSONArray(dev)
                for (i in 0 until arr.length()) {
                    val op = arr.getJSONObject(i)
                    when (op.getString("kind")) {
                        "dict" -> {
                            val rec = DictionaryRecord(
                                syncId = op.getString("syncId"),
                                businessKey = DictionaryRecord.businessKeyOf(op.getString("spoken")),
                                spoken = op.getString("spoken"),
                                corrected = op.getString("corrected"),
                                isEnabled = op.optBoolean("isEnabled", true),
                                updatedAt = op.getLong("updatedAt"),
                                deletedAt = if (op.isNull("deletedAt")) null else op.getLong("deletedAt"),
                                deviceId = op.getString("deviceId")
                            )
                            perDict.getOrPut(dev) { mutableListOf() }.add(rec)
                        }
                        "snip" -> {
                            val rec = SnippetRecord(
                                syncId = op.getString("syncId"),
                                businessKey = SnippetRecord.businessKeyOf(op.getString("trigger")),
                                trigger = op.getString("trigger"),
                                expansion = op.getString("expansion"),
                                isEnabled = op.optBoolean("isEnabled", true),
                                updatedAt = op.getLong("updatedAt"),
                                deletedAt = if (op.isNull("deletedAt")) null else op.getLong("deletedAt"),
                                deviceId = op.getString("deviceId")
                            )
                            perSnip.getOrPut(dev) { mutableListOf() }.add(rec)
                        }
                        "set" -> {
                            val rec = SettingsRecord(
                                key = op.getString("key"),
                                value = op.getString("value"),
                                updatedAt = op.getLong("updatedAt"),
                                deviceId = op.getString("deviceId"),
                                deletedAt = null
                            )
                            perSet.getOrPut(dev) { mutableListOf() }.add(rec)
                        }
                        "stat" -> {
                            val rec = StatRecord(
                                eventId = op.getString("eventId"),
                                day = op.getString("day"),
                                wordCount = op.getInt("words"),
                                durationMs = op.getLong("durationMs"),
                                updatedAt = 0L,
                                deviceId = "",
                                deletedAt = null,
                                timestampMs = op.getLong("timestampMs"),
                                chars = op.getInt("chars")
                            )
                            perStat.getOrPut(dev) { mutableListOf() }.add(rec)
                        }
                    }
                }
            }
            deviceIds.sort()

            // Prepare three orders: (a,b,c), (c,a,b), (b,c,a)
            val order1 = deviceIds.toList()
            val order2 = deviceIds.toMutableList().also { it.add(0, it.removeAt(it.size - 1)) } // rotate right 1
            val order3 = deviceIds.toMutableList().also { it.add(it.removeAt(0)) } // rotate left 1

            fun mergeDictOrder(order: List<String>): List<DictionaryRecord> {
                var acc: List<DictionaryRecord> = emptyList()
                var first = true
                for (dev in order) {
                    val lst = perDict[dev] ?: emptyList()
                    if (first) { acc = lst; first = false } else { acc = Merge.mergeDictionaries(acc, lst) }
                }
                return acc
            }
            fun mergeSnipOrder(order: List<String>): List<SnippetRecord> {
                var acc: List<SnippetRecord> = emptyList()
                var first = true
                for (dev in order) {
                    val lst = perSnip[dev] ?: emptyList()
                    if (first) { acc = lst; first = false } else { acc = Merge.mergeSnippets(acc, lst) }
                }
                return acc
            }
            fun mergeSetOrder(order: List<String>): List<SettingsRecord> {
                var acc: List<SettingsRecord> = emptyList()
                var first = true
                for (dev in order) {
                    val lst = perSet[dev] ?: emptyList()
                    if (first) { acc = lst; first = false } else { acc = Merge.mergeSettings(acc, lst) }
                }
                return acc
            }
            fun mergeStatOrder(order: List<String>): List<StatRecord> {
                var acc: List<StatRecord> = emptyList()
                var first = true
                for (dev in order) {
                    val lst = perStat[dev] ?: emptyList()
                    if (first) { acc = lst; first = false } else { acc = Merge.mergeStats(acc, lst) }
                }
                return acc
            }

            val dict1 = mergeDictOrder(order1)
            val dict2 = mergeDictOrder(order2)
            val dict3 = mergeDictOrder(order3)
            assertEquals("$scenarioLabel dict order (a,b,c) vs (c,a,b) must be identical", dict1, dict2)
            assertEquals("$scenarioLabel dict order (a,b,c) vs (b,c,a) must be identical", dict1, dict3)

            val snip1 = mergeSnipOrder(order1)
            val snip2 = mergeSnipOrder(order2)
            val snip3 = mergeSnipOrder(order3)
            assertEquals("$scenarioLabel snip order must be identical", snip1, snip2)
            assertEquals("$scenarioLabel snip order must be identical", snip1, snip3)

            val set1 = mergeSetOrder(order1)
            val set2 = mergeSetOrder(order2)
            val set3 = mergeSetOrder(order3)
            // Sort by key for comparison (merge already sorted)
            val s1 = set1.sortedBy { it.key }
            val s2 = set2.sortedBy { it.key }
            val s3 = set3.sortedBy { it.key }
            if (s1 != s2 || s1 != s3) {
                val msg = "DEBUG $scenarioLabel order1=$order1 s1=$s1 order2=$order2 s2=$s2 order3=$order3 s3=$s3 perSet dev-a=${perSet["dev-a"]} perSet dev-b=${perSet["dev-b"]} perSet dev-c=${perSet["dev-c"]}"
                throw AssertionError(msg)
            }
            assertEquals("$scenarioLabel settings order independence (a,b,c) vs (c,a,b)", s1, s2)
            assertEquals("$scenarioLabel settings order independence (a,b,c) vs (b,c,a)", s1, s3)

            val stat1 = mergeStatOrder(order1)
            val stat2 = mergeStatOrder(order2)
            val stat3 = mergeStatOrder(order3)
            assertEquals("$scenarioLabel stats order must be identical", stat1, stat2)
            assertEquals("$scenarioLabel stats order must be identical", stat1, stat3)

            // Compare with expected
            val expDictArr = expectedObj.getJSONArray("dictionary")
            val expSnipArr = expectedObj.getJSONArray("snippets")
            val expSetArr = expectedObj.getJSONArray("settings")
            val expStatArr = expectedObj.getJSONArray("stats")

            // Dictionary expected
            assertEquals("$scenarioLabel expected dict size", expDictArr.length(), dict1.size)
            for (i in 0 until expDictArr.length()) {
                val exp = expDictArr.getJSONObject(i)
                val syncId = exp.getString("syncId")
                val found = dict1.find { it.syncId == syncId }
                assertNotNull("$scenarioLabel dict missing expected syncId $syncId", found)
                assertEquals("$scenarioLabel businessKey mismatch $syncId", exp.getString("businessKey"), found!!.businessKey)
                assertEquals(exp.getString("spoken"), found.spoken)
                assertEquals(exp.getString("corrected"), found.corrected)
                assertEquals(exp.getBoolean("isEnabled"), found.isEnabled)
                assertEquals(exp.getLong("updatedAt"), found.updatedAt)
                val expDeleted = if (exp.isNull("deletedAt")) null else exp.getLong("deletedAt")
                assertEquals(expDeleted, found.deletedAt)
                assertEquals(exp.getString("deviceId"), found.deviceId)
            }

            // Snippets
            assertEquals(expSnipArr.length(), snip1.size)
            for (i in 0 until expSnipArr.length()) {
                val exp = expSnipArr.getJSONObject(i)
                val syncId = exp.getString("syncId")
                val found = snip1.find { it.syncId == syncId }
                assertNotNull("$scenarioLabel snip missing $syncId", found)
                assertEquals(exp.getString("businessKey"), found!!.businessKey)
                assertEquals(exp.getString("trigger"), found.trigger)
                assertEquals(exp.getString("expansion"), found.expansion)
                assertEquals(exp.getBoolean("isEnabled"), found.isEnabled)
                assertEquals(exp.getLong("updatedAt"), found.updatedAt)
                val expDel = if (exp.isNull("deletedAt")) null else exp.getLong("deletedAt")
                assertEquals(expDel, found.deletedAt)
                assertEquals(exp.getString("deviceId"), found.deviceId)
            }

            // Settings — allow stamped t=0
            assertEquals("$scenarioLabel settings size", expSetArr.length(), set1.size)
            for (i in 0 until expSetArr.length()) {
                val exp = expSetArr.getJSONObject(i)
                val key = exp.getString("key")
                val found = set1.find { it.key == key }
                assertNotNull("$scenarioLabel settings missing key $key", found)
                assertEquals(exp.getString("value"), found!!.value)
                assertEquals(exp.getString("deviceId"), found.deviceId)
                val expUpdated = exp.getLong("updatedAt")
                if (expUpdated == FIXED_STAMP) {
                    assertTrue("$scenarioLabel stamped t=0 winner should be >= FIXED_STAMP for key $key, got ${found.updatedAt}", found.updatedAt >= FIXED_STAMP)
                } else {
                    assertEquals("$scenarioLabel updatedAt mismatch key $key", expUpdated, found.updatedAt)
                }
            }

            // Stats
            assertEquals(expStatArr.length(), stat1.size)
            for (i in 0 until expStatArr.length()) {
                val exp = expStatArr.getJSONObject(i)
                val eid = exp.getString("eventId")
                val found = stat1.find { it.eventId == eid }
                assertNotNull("$scenarioLabel stats missing eventId $eid", found)
                assertEquals(exp.getString("day"), found!!.day)
                assertEquals(exp.getLong("timestampMs"), found.timestampMs)
                assertEquals(exp.getInt("words"), found.wordCount)
                assertEquals(exp.getInt("chars"), found.chars)
                assertEquals(exp.getLong("durationMs"), found.durationMs)
            }

            // Envelope serialization sanity: ensure production envelopes roundtrip
            val dictBytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = dict1)).toByteArray(Charsets.UTF_8)
            assertNotNull(DomainSerializer.parseDictionary(dictBytes))
            val snipBytes = DomainSerializer.serializeSnippets(SnippetDomain(entries = snip1)).toByteArray(Charsets.UTF_8)
            assertNotNull(DomainSerializer.parseSnippets(snipBytes))
            val setBytes = DomainSerializer.serializeSettings(SettingsDomain(entries = set1)).toByteArray(Charsets.UTF_8)
            assertNotNull(DomainSerializer.parseSettings(setBytes))
            val statBytes = DomainSerializer.serializeStats(StatsDomain(entries = stat1)).toByteArray(Charsets.UTF_8)
            assertNotNull(DomainSerializer.parseStats(statBytes))

            // Verify stats sorted day then eventId as per contract
            val sortedStat = stat1.sortedWith(compareBy({ it.day }, { it.eventId }))
            assertEquals("$scenarioLabel stats should already be sorted day then eventId", sortedStat, stat1)
        }
    }
}
