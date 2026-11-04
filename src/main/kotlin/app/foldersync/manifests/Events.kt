package app.foldersync.manifests

import app.foldersync.identity.AppUser
import app.foldersync.spaces.SpaceService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Revision notifications. Purely informational: receiving one tells the page that the server moved. It never starts a
 * scan, transfer or resume; the user's click does.
 */
@Service
class EventsService(private val spaces: SpaceService) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(ownerId: String, spaceId: String): SseEmitter {
        val space = spaces.require(spaceId, ownerId)
        val emitter = SseEmitter(30 * 60_000L)
        val list = emitters.computeIfAbsent(spaceId) { CopyOnWriteArrayList() }
        list.add(emitter)
        val remove = Runnable { list.remove(emitter) }
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError { remove.run() }
        send(emitter, space.revision)
        return emitter
    }

    /** Defers delivery until the surrounding transaction has committed, so listeners never see an unpublished revision. */
    fun revisionChanged(spaceId: String, revision: Long) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = publish(spaceId, revision)
            })
        } else publish(spaceId, revision)
    }

    private fun publish(spaceId: String, revision: Long) {
        emitters[spaceId]?.forEach { send(it, revision) }
    }

    fun heartbeat() {
        emitters.values.forEach { list -> list.forEach { e -> try { e.send(SseEmitter.event().comment("keepalive")) } catch (_: Exception) { list.remove(e) } } }
    }

    private fun send(emitter: SseEmitter, revision: Long) {
        try {
            emitter.send(SseEmitter.event().name("revision").data(revision.toString(), MediaType.TEXT_PLAIN))
        } catch (e: Exception) {
            log.debug("dropping event stream: {}", e.message)
            emitter.completeWithError(e)
        }
    }
}

@RestController
@RequestMapping("/api/spaces/{spaceId}")
class EventsController(private val events: EventsService) {
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@AuthenticationPrincipal u: AppUser, @PathVariable spaceId: String): SseEmitter = events.subscribe(u.id, spaceId)
}
