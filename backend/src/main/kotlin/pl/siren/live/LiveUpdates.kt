package pl.siren.live

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/** Server-sent events to admin panels: a small "something changed" signal, clients refetch. */
@Component
class LiveUpdates {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters += emitter
        emitter.onCompletion { emitters -= emitter }
        emitter.onTimeout { emitters -= emitter }
        emitter.onError { emitters -= emitter }
        send(emitter, "hello", "connected")
        return emitter
    }

    /** Sends after the surrounding transaction commits, so clients never refetch stale data. */
    fun publish(topic: String) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = broadcast(topic)
            })
        } else {
            broadcast(topic)
        }
    }

    private fun broadcast(topic: String) {
        emitters.forEach { send(it, "change", topic) }
    }

    @Scheduled(fixedRate = 20_000)
    fun heartbeat() {
        emitters.forEach { send(it, "ping", "") }
    }

    private fun send(emitter: SseEmitter, name: String, data: String) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data))
        } catch (e: Exception) {
            log.debug("Dropping SSE subscriber: {}", e.message)
            emitters -= emitter
        }
    }
}
