package app.locationjournal.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 was the first native schema. Version 2 adds visit confidence, which is derived
 * from GPS spread. The migration is kept so existing native installs upgrade without data loss.
 */
@Database(
    entities = [
        PointEntity::class,
        PlaceEntity::class,
        VisitEntity::class,
        PhotoEntity::class,
        JournalEntryEntity::class,
        ReelStateEntity::class,
        ReelCommentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao
    abstract fun placeDao(): PlaceDao
    abstract fun visitDao(): VisitDao
    abstract fun photoDao(): PhotoDao
    abstract fun entryDao(): EntryDao
    abstract fun reelDao(): ReelDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visits ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
            }
        }
    }
}
