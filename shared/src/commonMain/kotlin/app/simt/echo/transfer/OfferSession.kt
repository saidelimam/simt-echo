package app.simt.echo.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Today's transport window: one offer is served for at most ten minutes. */
const val OFFER_LIFETIME_MS = 600_000L

/**
 * The one offer this install is serving, with the identity that owns it
 * (plan 0005 §4.5).
 *
 * A generation is the only thing that may publish, report progress or
 * claim completion: an offer that was replaced can never write to the
 * current one, however late its callback arrives.
 */
data class ActiveOffer(
    val generation: Long,
    val qrUri: String,
    val host: String,
    val port: Int,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    fun isExpiredAt(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** The invitation is a bearer capability; only its size is diagnostic. */
    override fun toString(): String =
        "ActiveOffer(generation=$generation, uri=<redacted>, expiresAtMs=$expiresAtMs)"
}

/** An offer was stopped or replaced before it could be published. */
class OfferSupersededException : Exception("sharing was replaced before it started")

/**
 * Serializes every offer mutation under one owner (plan 0005 §4.5).
 *
 * Two re-offers cannot interleave: [replace] takes the session lock across
 * preparation, so whichever call is newest ends up published, and the
 * older one stops the server it started instead of being served beside it.
 *
 * Invalidation is synchronous and lock-free on purpose — the screen must
 * stop showing a QR (and stop publishing it over NFC) the instant the user
 * changes a setting, not when a coroutine gets scheduled.
 *
 * The session is owned by the caller's single-threaded scope
 * (`viewModelScope` on the main thread), like the ViewModels that drive it;
 * the mutex exists to serialize preparation, not to make fields atomic.
 */
class OfferSession(
    private val nowMs: () -> Long = { 0L },
    private val lifetimeMs: Long = OFFER_LIFETIME_MS,
    /** Stops whatever the transport is serving right now. */
    private val stopServing: () -> Unit,
) {
    private val prepareLock = Mutex()
    private var generation: Long = 0L
    private var active: ActiveOffer? = null
    private var preparing: Boolean = false

    /** The offer being served, or null while preparing or after a stop. */
    val current: ActiveOffer? get() = active

    val currentGeneration: Long get() = generation

    /** Whether [candidate] still owns the session. */
    fun isCurrent(candidate: Long): Boolean = candidate == generation

    /**
     * Stops serving this instant and burns the generation, so nothing
     * prepared under it can ever be published. Returns the new generation.
     */
    fun invalidate(): Long {
        generation += 1
        val wasOurs = active != null || preparing
        active = null
        if (wasOurs) stopServing()
        return generation
    }

    /**
     * Prepares a replacement offer under a fresh generation and publishes
     * it only if it is still the newest. [prepare] returns null to abandon
     * an offer it knows is stale; either way nothing stale is ever served.
     */
    suspend fun replace(prepare: suspend (Long) -> ActiveOffer?): ActiveOffer? {
        val mine = invalidate()
        return prepareLock.withLock {
            if (!isCurrent(mine)) {
                stopServing()
                return@withLock null
            }
            preparing = true
            try {
                val offer = prepare(mine)
                if (offer == null || !isCurrent(mine)) {
                    // Superseded while preparing: stop whatever this call
                    // started, and never publish it.
                    stopServing()
                    return@withLock null
                }
                active = offer
                offer
            } catch (failure: Throwable) {
                // A failed preparation must leave nothing serving.
                if (isCurrent(mine)) {
                    active = null
                    generation += 1
                }
                stopServing()
                throw failure
            } finally {
                preparing = false
            }
        }
    }

    /** Stamps an offer with this session's lifetime; the caller owns the invite. */
    fun stamp(generation: Long, qrUri: String, host: String, port: Int): ActiveOffer {
        val now = nowMs()
        return ActiveOffer(
            generation = generation,
            qrUri = qrUri,
            host = host,
            port = port,
            createdAtMs = now,
            expiresAtMs = now + lifetimeMs,
        )
    }

    /**
     * Drops an offer that outlived its window. Returns the expired
     * generation, or null when there was nothing to expire.
     */
    fun expireIfNeeded(): Long? {
        val offer = active ?: return null
        if (!offer.isExpiredAt(nowMs())) return null
        invalidate()
        return offer.generation
    }

    /** Stops serving; safe to call any number of times. */
    fun stop() {
        invalidate()
    }
}
