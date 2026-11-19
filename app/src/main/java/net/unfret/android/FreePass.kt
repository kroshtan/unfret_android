package net.unfret.android

/**
 * The one thing the app puts in Android's backup: whether this phone already received its free checks (a yes, nothing
 * else). On the phone it is [BackedUpFlag]; it travels only in the user's own Google backup and device transfers.
 */
interface FreeChecksFlag {
    /** Read fresh each time: Android's restore writes it at install time, before the app first runs. */
    fun received(): Boolean

    fun markReceived()
}

/** How a new pass is asked for: the challenge, then the pass (on the phone, [UnfretApi]'s key endpoints). */
interface KeyMaker {
    fun challenge(): KeyChallenge

    fun create(challenge: String, solution: String, free: Boolean): IssuedKey
}

/** [KeyMaker] over the API at [base]. */
class ApiKeyMaker(private val base: String, private val transport: Transport) : KeyMaker {
    override fun challenge() = UnfretApi.keyChallenge(base, transport)

    override fun create(challenge: String, solution: String, free: Boolean) =
        UnfretApi.createKey(base, transport, challenge, solution, free)
}

/**
 * The app's own pass, made the way the extension makes its free pass on install (its `shared/new-pass.ts`): the proof
 * of work, then `POST /v1/keys` with `free: true`, unless this phone already received its free checks ([flag], restored
 * from the user's backup after a reinstall): then the pass is made without them, and the app says why. The server
 * gives free checks only within its per-network daily limit, so a pass asked with them may still come with none.
 *
 * Nothing is made while a pass is stored (pasted, from a link, or restored): that one is used.
 */
class FreePass(
    private val vault: KeyVault,
    private val flag: FreeChecksFlag,
    private val keys: KeyMaker,
    private val solve: (KeyChallenge, (Long, Long) -> Unit) -> String = { c, progress ->
        ProofOfWork.solve(c.challenge, c.bits, onProgress = progress)
    },
) {
    sealed interface Outcome {
        /** The new pass came with [checks] free checks. */
        data class Free(val checks: Int) : Outcome

        /** This phone had its free checks already: the new pass starts without them. */
        data object HadFree : Outcome

        /** Free checks were asked for but there were none to give just now (the server's daily limit). */
        data object NoneToday : Outcome

        /** A pass is on the phone already: nothing was made. */
        data object HasPass : Outcome

        /** No pass was made: unreachable, refused, or the screen went away. */
        data class Failed(val problem: Problem) : Outcome
    }

    /** Make the pass and keep it. Blocks for the proof of work; [onProgress] gets (tried, expected) now and then. */
    fun make(onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        if (vault.load() != null) return Outcome.HasPass
        val ask = !flag.received()
        val issued = try {
            val challenge = keys.challenge()
            keys.create(challenge.challenge, solve(challenge, onProgress), ask)
        } catch (_: InterruptedException) {
            // The screen went away: nothing was made, and nothing is shown.
            Thread.currentThread().interrupt()
            return Outcome.Failed(Problem.OFFLINE)
        } catch (e: Exception) {
            return Outcome.Failed(Problem.of(e))
        }
        val key = ApiKeys.normalize(issued.key)
        if (key == null || key != issued.key) return Outcome.Failed(Problem.FAILED)
        // A pass pasted (or taken from a link) meanwhile wins: the user chose it.
        if (vault.load() != null) return Outcome.HasPass
        vault.save(key, issued.recoveryCode)
        // As the extension: the flag is set once free checks were given, never for a pass that came without them
        // because of the daily limit (a later pass may still get them).
        if (issued.freeChecks > 0) flag.markReceived()
        return when {
            issued.freeChecks > 0 -> Outcome.Free(issued.freeChecks)
            ask -> Outcome.NoneToday
            else -> Outcome.HadFree
        }
    }
}

/**
 * What the home screen does next, in order: the intro (consent) first, then a pass offered by a link (asked about,
 * never taken silently), then, once, the free pass; otherwise the home screen as it is.
 */
enum class FirstLaunchStep { INTRO, ASK_OFFERED_PASS, MAKE_FREE_PASS, HOME }

fun firstLaunchStep(consented: Boolean, offeredPass: Boolean, hasPass: Boolean, firstPassDone: Boolean): FirstLaunchStep =
    when {
        !consented -> FirstLaunchStep.INTRO
        offeredPass -> FirstLaunchStep.ASK_OFFERED_PASS
        !hasPass && !firstPassDone -> FirstLaunchStep.MAKE_FREE_PASS
        else -> FirstLaunchStep.HOME
    }
