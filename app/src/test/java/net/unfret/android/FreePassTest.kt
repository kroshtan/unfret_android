package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreePassTest {
    private val challenge = """{"challenge":"1900000000.0123456789abcdef.8.mac","bits":8,"expires_at":1900000000.0}"""

    private fun issued(free: Int, key: String = KEY_A) =
        """{"key_id":"${key.drop(4).take(10)}","key":"$key","recovery_code":"ufr_${key.drop(4).take(10)}_${"r".repeat(52)}","account":"key:x","balance_usd_micros":0,"free_checks":$free}"""

    private fun server(free: Int, key: String = KEY_A) = RoutedTransport(
        mapOf(
            "GET /v1/keys/challenge" to (200 to challenge),
            "POST /v1/keys" to (201 to issued(free, key)),
        ),
    )

    private fun freePass(vault: KeyVault, flag: FreeChecksFlag, transport: Transport) =
        FreePass(vault, flag, ApiKeyMaker(UnfretApi.PRODUCTION, transport))

    private fun sentBody(transport: RoutedTransport) = JSONObject(transport.sent.single { it.method == "POST" }.body!!)

    @Test
    fun `first launch without the flag makes a pass with free checks, and keeps it`() {
        val vault = KeyVault(MapStore(), XorBox())
        val flag = MemoryFlag()
        val transport = server(free = 5)

        val outcome = freePass(vault, flag, transport).make()

        assertEquals(FreePass.Outcome.Free(5), outcome)
        assertEquals(listOf("GET /v1/keys/challenge", "POST /v1/keys"), transport.paths())
        val body = sentBody(transport)
        assertTrue(body.getBoolean("free"))
        assertEquals(setOf("challenge", "solution", "free"), body.keys().asSequence().toSet())
        // A real solution of the challenge, as the server checks it.
        assertTrue(ProofOfWork.solves(body.getString("challenge"), body.getString("solution"), 8))
        // Nothing about the phone, and no pass yet.
        transport.sent.forEach { assertFalse(it.headers.containsKey("Authorization")) }
        assertEquals(KEY_A, vault.load())
        assertTrue(vault.recoveryCode()!!.startsWith("ufr_"))
        assertTrue(flag.received())
    }

    @Test
    fun `with the restored flag the pass is made without free checks, and the app says why`() {
        val vault = KeyVault(MapStore(), XorBox())
        val flag = MemoryFlag(true)
        val transport = server(free = 0)

        val outcome = freePass(vault, flag, transport).make()

        assertEquals(FreePass.Outcome.HadFree, outcome)
        assertFalse(sentBody(transport).getBoolean("free"))
        assertEquals(KEY_A, vault.load())
        assertTrue(flag.received())
    }

    @Test
    fun `a pass asked with free checks that comes with none says so, and leaves the flag unset`() {
        val vault = KeyVault(MapStore(), XorBox())
        val flag = MemoryFlag()
        val transport = server(free = 0)

        val outcome = freePass(vault, flag, transport).make()

        assertEquals(FreePass.Outcome.NoneToday, outcome)
        assertTrue(sentBody(transport).getBoolean("free"))
        assertEquals(KEY_A, vault.load())
        // As the extension: a later pass may still get them.
        assertFalse(flag.received())
    }

    @Test
    fun `no pass is made when one is on the phone already`() {
        val vault = KeyVault(MapStore(), XorBox())
        vault.save(KEY_B)
        val transport = server(free = 5)

        assertEquals(FreePass.Outcome.HasPass, freePass(vault, MemoryFlag(), transport).make())
        assertTrue(transport.sent.isEmpty())
        assertEquals(KEY_B, vault.load())
    }

    @Test
    fun `a pass pasted while this one was made wins`() {
        val vault = KeyVault(MapStore(), XorBox())
        val flag = MemoryFlag()
        val pasting = FreePass(vault, flag, ApiKeyMaker(UnfretApi.PRODUCTION, server(free = 5)), { c, _ ->
            vault.save(KEY_B)
            ProofOfWork.solve(c.challenge, c.bits)
        })

        assertEquals(FreePass.Outcome.HasPass, pasting.make())
        assertEquals(KEY_B, vault.load())
        assertNull(vault.recoveryCode())
        assertFalse(flag.received())
    }

    @Test
    fun `unreachable, refused or a malformed answer keeps nothing`() {
        fun attempt(transport: Transport): Pair<FreePass.Outcome, KeyVault> {
            val vault = KeyVault(MapStore(), XorBox())
            return freePass(vault, MemoryFlag(), transport).make() to vault
        }

        val (offline, v1) = attempt(RoutedTransport(emptyMap()))
        assertEquals(FreePass.Outcome.Failed(Problem.OFFLINE), offline)
        assertNull(v1.load())

        val tooMany = RoutedTransport(
            mapOf(
                "GET /v1/keys/challenge" to (200 to challenge),
                "POST /v1/keys" to (429 to """{"detail":"Too many new keys from here today; try again tomorrow."}"""),
            ),
        )
        val (refused, v2) = attempt(tooMany)
        assertEquals(FreePass.Outcome.Failed(Problem.FAILED), refused)
        assertNull(v2.load())

        val (off, _) = attempt(RoutedTransport(mapOf("GET /v1/keys/challenge" to (503 to """{"detail":"API keys are not available yet."}"""))))
        assertEquals(FreePass.Outcome.Failed(Problem.SERVICE_OFF), off)

        val (bad, v3) = attempt(server(free = 5, key = "ufk_NOTAKEY"))
        assertEquals(FreePass.Outcome.Failed(Problem.FAILED), bad)
        assertNull(v3.load())
    }

    @Test
    fun `first launch is the intro, then a pass from a link, then the free pass, once`() {
        // Not agreed yet: the intro first, even when a link brought a pass.
        assertEquals(FirstLaunchStep.INTRO, firstLaunchStep(consented = false, offeredPass = true, hasPass = false, firstPassDone = false))
        assertEquals(FirstLaunchStep.INTRO, firstLaunchStep(consented = false, offeredPass = false, hasPass = false, firstPassDone = false))
        // Agreed with a pass from a link: the question, not a free pass.
        assertEquals(FirstLaunchStep.ASK_OFFERED_PASS, firstLaunchStep(consented = true, offeredPass = true, hasPass = false, firstPassDone = false))
        assertEquals(FirstLaunchStep.ASK_OFFERED_PASS, firstLaunchStep(consented = true, offeredPass = true, hasPass = true, firstPassDone = true))
        // Agreed, nothing else: the free pass.
        assertEquals(FirstLaunchStep.MAKE_FREE_PASS, firstLaunchStep(consented = true, offeredPass = false, hasPass = false, firstPassDone = false))
        // A pass on the phone (pasted, from a link, restored) or the first pass settled: home.
        assertEquals(FirstLaunchStep.HOME, firstLaunchStep(consented = true, offeredPass = false, hasPass = true, firstPassDone = false))
        assertEquals(FirstLaunchStep.HOME, firstLaunchStep(consented = true, offeredPass = false, hasPass = false, firstPassDone = true))
    }

    @Test
    fun `an issued key never shows its secrets as text`() {
        val issued = IssuedKey.parse(JSONObject(issued(5)))
        assertFalse(issued.toString().contains(KEY_A))
        assertFalse(issued.toString().contains(issued.recoveryCode))
    }
}
