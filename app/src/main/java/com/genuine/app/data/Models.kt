package com.genuine.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("birth_date") val birthDate: String,
    val gender: String,
    @SerialName("interested_in") val interestedIn: List<String>,
    val bio: String? = null,
    val zone: String,
    val verification: String,
    val interests: List<String> = emptyList(),
    @SerialName("relationship_goal") val relationshipGoal: String? = null,
    val kids: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    val education: String? = null,
    @SerialName("job_title") val jobTitle: String? = null,
    val languages: List<String> = emptyList(),
    val hometown: String? = null,
    val drinking: String? = null,
    val smoking: String? = null,
    val exercise: String? = null,
    val diet: String? = null,
    val prompts: List<PromptAnswer> = emptyList(),
)

/** Only the columns the client is allowed to insert (see column grants in schema.sql). */
@Serializable
data class ProfileInput(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("birth_date") val birthDate: String, // yyyy-MM-dd
    val gender: String,                               // man | woman | nonbinary
    @SerialName("interested_in") val interestedIn: List<String>,
    val bio: String?,
    val zone: String = "omr-velachery",
)

@Serializable
data class ProfilePrivate(
    val id: String,
    val lat: Double?,
    val lng: Double?,
    val paused: Boolean = false,
    @SerialName("min_age") val minAge: Int = 21,
    @SerialName("max_age") val maxAge: Int = 35,
    @SerialName("max_distance_km") val maxDistanceKm: Int = 10,
)

@Serializable
data class PhotoInput(@SerialName("storage_path") val storagePath: String, val position: Int)

@Serializable
data class PhotoRef(val id: String)

@Serializable
data class Suggestion(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val age: Int,
    val bio: String? = null,
    val interests: List<String> = emptyList(),
    @SerialName("distance_km") val distanceKm: Int,
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("relationship_goal") val relationshipGoal: String? = null,
)

@Serializable
data class SwipeInput(val target: String, val liked: Boolean)

@Serializable
data class MatchRef(val id: String)

@Serializable
data class MatchSummary(
    @SerialName("match_id") val matchId: String,
    @SerialName("other_id") val otherId: String,
    @SerialName("other_name") val otherName: String,
    @SerialName("other_photo") val otherPhoto: String? = null,
    val status: String,
    @SerialName("chat_expires_at") val chatExpiresAt: String? = null,
    @SerialName("last_message") val lastMessage: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("meetup_status") val meetupStatus: String? = null,
    @SerialName("meetup_at") val meetupAt: String? = null,
    @SerialName("meetup_by_me") val meetupByMe: Boolean = false,
    @SerialName("checked_in") val checkedIn: Boolean = false,
    @SerialName("wants_again") val wantsAgain: Boolean = false,
    @SerialName("meetup_updated") val meetupUpdated: Boolean = false,
)

@Serializable
data class Message(
    val id: Long,
    @SerialName("match_id") val matchId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    val kind: String = "text",
    @SerialName("reply_to") val replyTo: Long? = null,
)

@Serializable
data class MessageInput(
    @SerialName("match_id") val matchId: String,
    val body: String,
    @SerialName("reply_to") val replyTo: Long? = null,
)

@Serializable
data class Meetup(
    val id: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("proposed_by") val proposedBy: String,
    @SerialName("venue_name") val venueName: String,
    @SerialName("scheduled_at") val scheduledAt: String,
    val status: String,
    val replaces: String? = null,
)

@Serializable
data class MeetupInput(
    @SerialName("match_id") val matchId: String,
    @SerialName("venue_name") val venueName: String,
    @SerialName("scheduled_at") val scheduledAt: String, // ISO-8601 with offset
)

@Serializable
data class CheckInInput(
    @SerialName("meetup_id") val meetupId: String,
    @SerialName("did_meet") val didMeet: Boolean,
    @SerialName("profile_accurate") val profileAccurate: Boolean?,
    @SerialName("felt_safe") val feltSafe: Boolean?,
    @SerialName("want_again") val wantAgain: Boolean?,
)

@Serializable
data class ReportInput(
    @SerialName("reported_id") val reportedId: String,
    val reason: String,
    val details: String? = null,
)

@Serializable
data class BlockInput(val blocked: String)

@Serializable
data class VerificationSession(val url: String)

@Serializable
data class MatchState(
    val id: String,
    val status: String,
    @SerialName("chat_expires_at") val chatExpiresAt: String? = null,
)

@Serializable
data class CheckInRef(@SerialName("meetup_id") val meetupId: String)

@Serializable
data class ProfileUpdate(
    @SerialName("display_name") val displayName: String,
    val bio: String?,
    @SerialName("interested_in") val interestedIn: List<String>,
    val interests: List<String>,
    @SerialName("relationship_goal") val relationshipGoal: String?,
    val kids: String?,
    @SerialName("height_cm") val heightCm: Int?,
    val education: String?,
    @SerialName("job_title") val jobTitle: String?,
    val languages: List<String>,
    val hometown: String?,
    val drinking: String?,
    val smoking: String?,
    val exercise: String?,
    val diet: String?,
    val prompts: List<PromptAnswer>,
)
@Serializable
data class DiscoverySettings(
    @SerialName("min_age") val minAge: Int,
    @SerialName("max_age") val maxAge: Int,
    @SerialName("max_distance_km") val maxDistanceKm: Int,
)

@Serializable
data class AreaUpdate(val lat: Double, val lng: Double, @SerialName("updated_at") val updatedAt: String)

@Serializable
data class Photo(
    val id: String,
    @SerialName("storage_path") val storagePath: String,
    val position: Int,
)

@Serializable
data class PublicProfile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val age: Int,
    val bio: String? = null,
    val interests: List<String> = emptyList(),
    @SerialName("distance_km") val distanceKm: Int? = null,
    @SerialName("photo_paths") val photoPaths: List<String> = emptyList(),
    @SerialName("relationship_goal") val relationshipGoal: String? = null,
    val kids: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    val education: String? = null,
    @SerialName("job_title") val jobTitle: String? = null,
    val languages: List<String> = emptyList(),
    val hometown: String? = null,
    val drinking: String? = null,
    val smoking: String? = null,
    val exercise: String? = null,
    val diet: String? = null,
    val prompts: List<PromptAnswer> = emptyList(),
)

@Serializable
data class PausedUpdate(val paused: Boolean)

@Serializable
data class PromptAnswer(val q: String, val a: String)

@Serializable
data class TypingEvent(val user: String, val typing: Boolean)

@Serializable
data class ReadState(
    @SerialName("user_id") val userId: String,
    @SerialName("last_read_id") val lastReadId: Long,
    @SerialName("last_delivered_id") val lastDeliveredId: Long,
)

@Serializable
data class PhotoPath(@SerialName("storage_path") val storagePath: String)