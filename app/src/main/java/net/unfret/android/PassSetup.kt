package net.unfret.android

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The app's own pass being made ([FreePass]): one at a time for the whole app, outliving a rotated or recreated home
 * screen, which attaches to it instead of asking the server for a second pass. Main thread only.
 */
object PassSetup {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running: Future<*>? = null
    private var listener: ((FreePass.Outcome) -> Unit)? = null

    /** The outcome nobody was listening for yet (the screen was being recreated). */
    private var unclaimed: FreePass.Outcome? = null

    val busy: Boolean get() = running != null

    /** Start making the pass (nothing if it is already being made). */
    fun start(freePass: FreePass) {
        if (running != null) return
        unclaimed = null
        running = worker.submit {
            val outcome = freePass.make()
            main.post {
                running = null
                val to = listener
                if (to != null) to(outcome) else unclaimed = outcome
            }
        }
    }

    /** Hear how it went; an outcome that came while no screen listened is delivered now. */
    fun listen(to: (FreePass.Outcome) -> Unit) {
        listener = to
        unclaimed?.let {
            unclaimed = null
            to(it)
        }
    }

    fun stopListening(to: (FreePass.Outcome) -> Unit) {
        if (listener === to) listener = null
    }
}
