package com.genuine.app.data

import com.genuine.app.ui.Routes
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.merge
import io.github.jan.supabase.postgrest.query.Count
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private val db get() = Supa.client
private val json = Json { ignoreUnknownKeys = true }

object AuthRepo {
    fun uid(): String = db.auth.currentUserOrNull()?.id ?: error("Not signed in")

    /** phone must be E.164, e.g. +919876543210 */
    suspend fun sendOtp(phone: String) = db.auth.signInWith(OTP) { this.phone = phone }

    suspend fun verifyOtp(phone: String, code: String) =
        db.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = phone, token = code)

    fun phone(): String? = db.auth.currentUserOrNull()?.phone

    /** Signs out on the server if reachable, and always clears the saved login on this phone. */
    suspend fun signOut() {
        PushRepo.unregister()
        runCatching { db.auth.signOut() }
        runCatching { db.auth.clearSession() }
    }

    /**
     * Waits (up to 10 s) until the login session is ready, e.g. while it
     * refreshes after the app returns from the background.
     */
    suspend fun awaitSignedIn() {
        withTimeoutOrNull(10_000.milliseconds) {
            db.auth.sessionStatus.first { it is SessionStatus.Authenticated }
        }
    }

    /** Permanently deletes this account. Throws if the server couldn't do it. */
    suspend fun deleteAccount() {
        PushRepo.unregister()                     // while we're still signed in
        db.functions.invoke("delete-account")     // throws on failure
        runCatching { db.auth.clearSession() }    // the account no longer exists
    }
}

object ProfileRepo {
    suspend fun myProfile(): Profile? =
        db.postgrest.rpc("get_my_profile").decodeList<Profile>().firstOrNull()

    suspend fun publicProfile(userId: String): PublicProfile? =
        db.postgrest.rpc("get_public_profile", buildJsonObject { put("p_user_id", userId) })
            .decodeList<PublicProfile>().firstOrNull()

    suspend fun create(input: ProfileInput, private: ProfilePrivate) {
        db.from("profiles").insert(input)
        db.from("profile_private").upsert(private)
    }

    suspend fun photoCount(): Int =
        db.from("photos").select { filter { eq("user_id", AuthRepo.uid()) } }
            .decodeList<PhotoRef>().size

    suspend fun myPhotos(): List<Photo> =
        db.from("photos").select {
            filter { eq("user_id", AuthRepo.uid()) }
            order("position", Order.ASCENDING)
        }.decodeList<Photo>()

    /** Adds a photo at the end. Used by both onboarding and the Profile tab. */
    suspend fun uploadPhoto(jpeg: ByteArray) {
        val next = (myPhotos().maxOfOrNull { it.position } ?: -1) + 1
        val path = "${AuthRepo.uid()}/${UUID.randomUUID()}.jpg"
        db.storage.from("photos").upload(path, jpeg) { upsert = false }
        try {
            db.from("photos").insert(PhotoInput(path, next))
        } catch (e: Exception) {
            // e.g. the 6-photo limit: don't leave an orphan file in Storage
            runCatching { db.storage.from("photos").delete(path) }
            throw e
        }
    }

    suspend fun deletePhoto(photo: Photo) {
        // Row first: the server refuses if it's the last photo, and then the file stays too
        db.from("photos").delete { filter { eq("id", photo.id) } }
        runCatching { db.storage.from("photos").delete(photo.storagePath) }
    }

    suspend fun setMainPhoto(photoId: String) {
        db.postgrest.rpc("set_main_photo", buildJsonObject { put("p_photo_id", photoId) })
    }

    suspend fun photoUrl(path: String): String =
        db.storage.from("photos").createSignedUrl(path, 1.hours)

    suspend fun startVerification(): String {
        val text = db.functions.invoke("start-verification").bodyAsText()
        return json.decodeFromString<VerificationSession>(text).url
    }

    /** Decides where a signed-in user belongs in onboarding. */
    suspend fun nextStep(): String {
        val p = myProfile() ?: return Routes.SETUP
        if (photoCount() == 0) return Routes.CAMERA
        if (p.verification != "verified") return Routes.VERIFY
        return Routes.HOME
    }

    suspend fun myPrivate(): ProfilePrivate? =
        db.from("profile_private").select { filter { eq("id", AuthRepo.uid()) } }
            .decodeSingleOrNull<ProfilePrivate>()

    suspend fun updateDetails(u: ProfileUpdate) {
        db.from("profiles").update(u) { filter { eq("id", AuthRepo.uid()) } }
    }

    suspend fun updateSettings(s: DiscoverySettings) {
        db.from("profile_private").update(s) { filter { eq("id", AuthRepo.uid()) } }
    }

    suspend fun updateArea(lat: Double, lng: Double) {
        db.from("profile_private").update(AreaUpdate(lat, lng, java.time.OffsetDateTime.now().toString())) {
            filter { eq("id", AuthRepo.uid()) }
        }
    }

    suspend fun setPaused(paused: Boolean) {
        db.from("profile_private").update(PausedUpdate(paused)) { filter { eq("id", AuthRepo.uid()) } }
    }
}

object DiscoverRepo {
    suspend fun suggestions(): List<Suggestion> =
        db.postgrest.rpc("get_daily_suggestions").decodeList<Suggestion>()

    /** Returns the match id if this like created a match, otherwise null. */
    suspend fun swipe(target: String, liked: Boolean): String? {
        db.from("swipes").insert(SwipeInput(target, liked))
        if (!liked) return null
        val me = AuthRepo.uid()
        val (a, b) = if (me < target) me to target else target to me
        return db.from("matches").select { filter { eq("user_a", a); eq("user_b", b) } }
            .decodeList<MatchRef>().firstOrNull()?.id
    }

    /** Likes + passes since midnight IST. Uses the existing swipes table and RLS. */
    suspend fun swipesUsedToday(): Int {
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val midnight = java.time.LocalDate.now(ist).atStartOfDay(ist).toOffsetDateTime().toString()
        return db.from("swipes").select {
            filter {
                eq("swiper", AuthRepo.uid())
                gte("created_at", midnight)
            }
            count(Count.EXACT)
            limit(1) // we only need the count header, not the rows
        }.countOrNull()?.toInt() ?: 0
    }
}

object ChatRepo {
    suspend fun myMatches(): List<MatchSummary> =
        db.postgrest.rpc("get_my_matches").decodeList<MatchSummary>()

    suspend fun history(matchId: String): List<Message> =
        db.from("messages").select {
            filter { eq("match_id", matchId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<Message>()

    /** Returns the saved message, so it can be shown immediately. */
    suspend fun send(matchId: String, body: String, replyTo: Long? = null): Message =
        db.from("messages").insert(MessageInput(matchId, body.trim(), replyTo)) { select() }
            .decodeSingle<Message>()

    /** Collect the flow first, then call channel.subscribe(). */
    fun live(matchId: String): Pair<RealtimeChannel, Flow<Message>> {
        val channel = db.channel("match-$matchId-${UUID.randomUUID()}")
        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("match_id", FilterOperator.EQ, matchId)
        }.map { it.decodeRecord<Message>() }
        return channel to flow
    }

    suspend fun unmatch(matchId: String) {
        db.postgrest.rpc("unmatch", buildJsonObject { put("p_match_id", matchId) })
    }

    /** Fires whenever a message or match the user can see changes. Collect first, then subscribe. */
    fun inboxEvents(): Pair<RealtimeChannel, Flow<Unit>> {
        val channel = db.channel("inbox-${AuthRepo.uid()}-${UUID.randomUUID()}")
        val newMessages = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "messages" }
        val matchChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "matches" }
        val meetupChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "meetups" }
        return channel to merge(newMessages.map { }, matchChanges.map { }, meetupChanges.map { })
    }

    suspend fun matchState(matchId: String): MatchState? =
        db.from("matches").select { filter { eq("id", matchId) } }.decodeSingleOrNull<MatchState>()

    suspend fun firstPhotoPath(userId: String): String? =
        db.from("photos").select {
            filter { eq("user_id", userId) }
            order("position", Order.ASCENDING)
            limit(1)
        }.decodeList<PhotoPath>().firstOrNull()?.storagePath

    suspend fun markRead(matchId: String) {
        db.postgrest.rpc("mark_read", buildJsonObject { put("p_match_id", matchId) })
    }

    suspend fun readStates(matchId: String): List<ReadState> =
        db.from("match_reads").select { filter { eq("match_id", matchId) } }.decodeList<ReadState>()

    suspend fun markDelivered() {
        db.postgrest.rpc("mark_delivered")
    }

}

object MeetupRepo {
    suspend fun latest(matchId: String): Meetup? =
        db.from("meetups").select {
            filter { eq("match_id", matchId) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<Meetup>().firstOrNull()

    suspend fun propose(input: MeetupInput) {
        db.from("meetups").insert(input)
    }

    suspend fun respond(meetupId: String, accept: Boolean) {
        db.postgrest.rpc("respond_meetup", buildJsonObject {
            put("p_meetup_id", meetupId)
            put("p_accept", accept)
        })
    }

    suspend fun checkIn(input: CheckInInput) {
        db.from("checkins").insert(input)
    }

    suspend fun cancel(meetupId: String) {
        db.postgrest.rpc("cancel_meetup", buildJsonObject { put("p_meetup_id", meetupId) })
    }

    suspend fun reschedule(meetupId: String, venue: String, scheduledAt: String) {
        db.postgrest.rpc("reschedule_meetup", buildJsonObject {
            put("p_meetup_id", meetupId)
            put("p_venue", venue)
            put("p_scheduled_at", scheduledAt)
        })
    }

    /** Fires on any insert/update to this match's meetups. Collect first, then subscribe. */
    fun live(matchId: String): Pair<RealtimeChannel, Flow<Unit>> {
        val channel = db.channel("meetups-$matchId-${UUID.randomUUID()}")
        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "meetups"
            filter("match_id", FilterOperator.EQ, matchId)
        }.map { }
        return channel to flow
    }

    suspend fun hasCheckedIn(meetupId: String): Boolean =
        db.from("checkins").select {
            filter {
                eq("meetup_id", meetupId)
                eq("user_id", AuthRepo.uid())
            }
        }.decodeList<CheckInRef>().isNotEmpty()

    suspend fun wantsAgain(matchId: String): Boolean =
        db.postgrest.rpc("wants_again", buildJsonObject { put("p_match", matchId) }).decodeAs<Boolean>()
}

object SafetyRepo {
    suspend fun report(userId: String, reason: String, details: String?) {
        db.from("reports").insert(ReportInput(userId, reason, details))
    }

    suspend fun block(userId: String) {
        db.from("blocks").insert(BlockInput(userId))
    }
}

object PushRepo {
    /** Registers this phone for the signed-in user. Safe to call repeatedly. */
    suspend fun register(token: String? = null) {
        if (db.auth.currentUserOrNull() == null) return // registered at next login instead
        val t = token ?: FirebaseMessaging.getInstance().token.await()
        db.postgrest.rpc("register_device", buildJsonObject { put("p_token", t) })
    }

    /** Stops notifications to this phone; called before signing out. */
    suspend fun unregister() {
        runCatching {
            val t = FirebaseMessaging.getInstance().token.await()
            db.postgrest.rpc("unregister_device", buildJsonObject { put("p_token", t) })
        }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }
}