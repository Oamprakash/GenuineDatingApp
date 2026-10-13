package com.genuine.app.push

import android.util.Log
import com.genuine.app.data.AuthRepo
import com.genuine.app.data.Supa
import com.genuine.app.data.TypingEvent
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One shared typing connection per chat. Each screen joins with its own name
 * ("owner") and leaves with the same name; the connection closes only when no
 * owner needs it. Leaving twice, or leaving without having joined, does nothing.
 */
object TypingHub {
    private val mutex = Mutex()
    private val channels = mutableMapOf<String, RealtimeChannel>()
    private val listeners = mutableMapOf<String, Job>()
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private val timeouts = mutableMapOf<String, Job>()

    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    /** Match ids where the *other* person is typing right now. */
    val typing: StateFlow<Set<String>> = _typing

    suspend fun join(matchId: String, owner: String) {
        val ch = mutex.withLock {
            owners.getOrPut(matchId) { mutableSetOf() }.add(owner)
            if (matchId in channels) return
            Supa.client.channel("typing:$matchId").also { ch ->
                channels[matchId] = ch
                listeners[matchId] = AppScope.launch {
                    ch.broadcastFlow<TypingEvent>(event = "typing").collect { e ->
                        val me = runCatching { AuthRepo.uid() }.getOrNull()
                        if (e.user != me) set(matchId, e.typing)
                    }
                }
            }
        }
        // Subscribe outside the lock, so a slow connection never holds up other screens
        AppScope.launch {
            val stillWanted = mutex.withLock { channels[matchId] === ch }
            if (!stillWanted) return@launch
            runCatching { ch.subscribe() }
                .onSuccess { Log.d("Typing", "subscribed $matchId") }
                .onFailure { Log.e("Typing", "subscribe failed $matchId", it) }
        }
    }

    suspend fun leave(matchId: String, owner: String) {
        val ch = mutex.withLock {
            val set = owners[matchId] ?: return
            if (!set.remove(owner)) return       // this owner never joined: nothing to do
            if (set.isNotEmpty()) return         // someone else still needs it
            owners.remove(matchId)
            listeners.remove(matchId)?.cancel()
            channels.remove(matchId)
        } ?: return
        runCatching { Supa.client.realtime.removeChannel(ch) }
        set(matchId, false)
        Log.d("Typing", "closed $matchId")
    }

    suspend fun send(matchId: String, typing: Boolean) {
        val me = runCatching { AuthRepo.uid() }.getOrNull() ?: return
        val ch = mutex.withLock { channels[matchId] }
        if (ch == null) {
            Log.w("Typing", "send skipped, no connection for $matchId")
            return
        }
        runCatching { ch.broadcast(event = "typing", message = TypingEvent(me, typing)) }
    }

    /** Their message arrived, so they're no longer typing. */
    fun clear(matchId: String) = set(matchId, false)

    @Synchronized
    private fun set(matchId: String, typing: Boolean) {
        timeouts.remove(matchId)?.cancel()
        _typing.update { if (typing) it + matchId else it - matchId }
        if (typing) {
            timeouts[matchId] = AppScope.launch {
                delay(4_000)
                _typing.update { it - matchId }
            }
        }
    }
}