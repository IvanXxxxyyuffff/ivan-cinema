package com.ivan.cinema.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watch_history")
data class WatchEntry(
    @PrimaryKey val vodKey: String,
    val name: String,
    val year: String,
    val pic: String,
    val sourceApi: String,
    val sourceName: String,
    /** 源内影片 id。缺了它，「继续观看」无法重建播放地址。仅本地保存。 */
    val vodId: String = "",
    val lineIndex: Int,
    val episodeIndex: Int,
    val episodeName: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /**
     * 归属用户：登录时是 Supabase uid，未登录是 Account.LOCAL_USER（"local"）。
     * 同一台设备上多账号时靠它隔离「继续观看」。
     */
    val userId: String = ""
)

/** 搜索历史：关键词去重，按最近使用排序。 */
@Entity(tableName = "search_history")
data class SearchEntry(
    @PrimaryKey val keyword: String,
    val lastUsedAt: Long
)

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SearchEntry)

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT 20")
    fun recent(): Flow<List<SearchEntry>>

    @Query("DELETE FROM search_history WHERE keyword = :kw")
    suspend fun delete(kw: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: WatchEntry)

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT 30")
    fun recent(): Flow<List<WatchEntry>>

    /**
     * 只读当前用户的历史（「继续观看」应改用这个）。
     * [recent] 是设备级的旧接口，会串到其他账号，仅作兼容保留。
     */
    @Query("SELECT * FROM watch_history WHERE userId = :userId ORDER BY updatedAt DESC LIMIT 30")
    fun recentFor(userId: String): Flow<List<WatchEntry>>

    @Query("SELECT * FROM watch_history WHERE vodKey = :key LIMIT 1")
    suspend fun get(key: String): WatchEntry?

    /** 只取属于该用户的记录，避免换账号后读到别人的进度。 */
    @Query("SELECT * FROM watch_history WHERE userId = :userId AND vodKey = :key LIMIT 1")
    suspend fun getFor(userId: String, key: String): WatchEntry?

    @Query("DELETE FROM watch_history WHERE vodKey = :key")
    suspend fun delete(key: String)
}

@Database(entities = [WatchEntry::class, SearchEntry::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun watchDao(): WatchDao
    abstract fun searchDao(): SearchDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDb::class.java, "ivan.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                .also { inst = it }
        }

        /** v1 → v2：新增搜索历史表，不动观看记录。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`keyword` TEXT NOT NULL, `lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`keyword`))"
                )
            }
        }

        /**
         * v2 → v3：观看记录补 vodId。
         * 没有它，「继续观看」只能拿着空 id 去请求详情，必然失败。
         * 老记录留空字符串，播放页会用片名回查兜底。
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `watch_history` ADD COLUMN `vodId` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v3 → v4：观看记录补 userId，实现同一台设备上多账号历史隔离。
         * 旧数据没有归属，统一回填给本地用户（Account.LOCAL_USER = "local"），
         * 否则升级后这些记录既不属于任何账号，「继续观看」会凭空消失。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `watch_history` ADD COLUMN `userId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "UPDATE `watch_history` SET `userId` = 'local' WHERE `userId` = ''"
                )
            }
        }
    }
}
