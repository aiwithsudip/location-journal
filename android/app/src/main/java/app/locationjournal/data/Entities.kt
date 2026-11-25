package app.locationjournal.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "points",
    indices = [Index("t"), Index("kind"), Index("hour")],
)
data class PointEntity(
    @PrimaryKey val id: String,
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float = 0f,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val kind: String = "sample",
    val hour: String? = null,
    val source: String = "native",
)

@Entity(
    tableName = "places",
    indices = [Index("name"), Index("lastSeen")],
)
data class PlaceEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lng: Double,
    val name: String? = null,
    val category: String = "Other",
    val visitCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val mergedInto: String? = null,
)

@Entity(
    tableName = "visits",
    indices = [Index("start"), Index("placeId"), Index("day")],
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val placeId: String,
    val day: String,
    val start: Long,
    val end: Long,
    val durationMs: Long,
    val lat: Double,
    val lng: Double,
    val samples: Int,
    val confidence: Float = 1f,
    val title: String? = null,
    val note: String? = null,
    val category: String? = null,
)

@Entity(
    tableName = "photos",
    indices = [Index("t"), Index("placeId"), Index("visitId")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val name: String,
    val appPath: String? = null,
    val sourceUri: String? = null,
    val t: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val caption: String? = null,
    val label: String? = null,
    val favorite: Boolean = false,
    val placeId: String? = null,
    val visitId: String? = null,
)

@Entity(
    tableName = "journal_entries",
    indices = [Index("time"), Index("placeId")],
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val category: String? = null,
    val time: Long,
    val placeId: String? = null,
    val visitId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "reel_states")
data class ReelStateEntity(
    @PrimaryKey val id: String,
    val liked: Boolean = false,
    val saved: Boolean = false,
    val likes: Int = 0,
    val views: Int = 0,
    val shares: Int = 0,
    val reshares: Int = 0,
    val index: Int = 0,
    val muted: Boolean = true,
)

@Entity(
    tableName = "reel_comments",
    indices = [Index("reelId")],
)
data class ReelCommentEntity(
    @PrimaryKey val id: String,
    val reelId: String,
    val author: String,
    val text: String,
    val time: Long,
    val mine: Boolean = false,
)
