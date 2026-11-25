package app.locationjournal.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PointDao {
    @Query("SELECT * FROM points WHERE t >= :from AND t < :to ORDER BY t ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE t >= :from AND t < :to ORDER BY t ASC")
    suspend fun between(from: Long, to: Long): List<PointEntity>

    @Query("SELECT * FROM points ORDER BY t DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PointEntity>>

    @Query("SELECT * FROM points ORDER BY t DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PointEntity>

    @Query("SELECT COUNT(*) FROM points")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(points: List<PointEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<PointEntity>)

    @Query("DELETE FROM points WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM points WHERE t >= :from AND t < :to")
    suspend fun deleteRange(from: Long, to: Long)

    @Query("DELETE FROM points")
    suspend fun deleteAll()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE mergedInto IS NULL ORDER BY visitCount DESC, lastSeen DESC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE mergedInto IS NULL ORDER BY visitCount DESC, lastSeen DESC")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE mergedInto IS NULL ORDER BY lastSeen DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlaceEntity>

    @Upsert
    suspend fun upsert(place: PlaceEntity)

    @Upsert
    suspend fun upsertAll(places: List<PlaceEntity>)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM places")
    suspend fun deleteAll()
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE start >= :from AND start < :to ORDER BY start DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE start >= :from AND start < :to ORDER BY start DESC")
    suspend fun between(from: Long, to: Long): List<VisitEntity>

    @Query("SELECT * FROM visits ORDER BY start DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits ORDER BY start ASC")
    suspend fun allVisits(): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun byId(id: String): VisitEntity?

    @Query("SELECT * FROM visits WHERE placeId = :placeId ORDER BY start DESC")
    suspend fun byPlace(placeId: String): List<VisitEntity>

    @Upsert
    suspend fun upsertAll(visits: List<VisitEntity>)

    @Query("UPDATE visits SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String?)

    @Query("UPDATE visits SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String?)

    @Query("UPDATE visits SET placeId = :placeId, category = :category WHERE id = :id")
    suspend fun reassign(id: String, placeId: String, category: String?)

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM visits WHERE start >= :from AND start < :to")
    suspend fun deleteRange(from: Long, to: Long)

    @Query("DELETE FROM visits")
    suspend fun deleteAll()
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY t DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE visitId = :visitId ORDER BY t DESC")
    suspend fun byVisit(visitId: String): List<PhotoEntity>

    @Upsert
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("UPDATE photos SET visitId = :visitId, placeId = :placeId WHERE id IN (:ids)")
    suspend fun attach(ids: List<String>, visitId: String?, placeId: String?)

    @Query("UPDATE photos SET caption = :caption, label = :label, favorite = :favorite WHERE id = :id")
    suspend fun updateMetadata(id: String, caption: String?, label: String?, favorite: Boolean)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM journal_entries ORDER BY time DESC")
    fun observeAll(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun byId(id: String): JournalEntryEntity?

    @Upsert
    suspend fun upsert(entry: JournalEntryEntity)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAll()
}

@Dao
interface ReelDao {
    @Query("SELECT * FROM reel_states")
    fun observeStates(): Flow<List<ReelStateEntity>>

    @Query("SELECT * FROM reel_states WHERE id = :id")
    suspend fun state(id: String): ReelStateEntity?

    @Upsert
    suspend fun upsertState(state: ReelStateEntity)

    @Query("SELECT * FROM reel_comments WHERE reelId = :reelId ORDER BY time ASC")
    fun observeComments(reelId: String): Flow<List<ReelCommentEntity>>

    @Query("SELECT * FROM reel_comments ORDER BY time ASC")
    fun observeAllComments(): Flow<List<ReelCommentEntity>>

    @Upsert
    suspend fun upsertComment(comment: ReelCommentEntity)

    @Query("DELETE FROM reel_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    @Query("DELETE FROM reel_comments")
    suspend fun deleteComments()

    @Query("DELETE FROM reel_states")
    suspend fun deleteStates()
}
