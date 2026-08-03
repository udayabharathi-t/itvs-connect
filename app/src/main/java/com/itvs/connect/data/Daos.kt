package com.itvs.connect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observeById(id: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getById(id: Long): RideEntity?

    @Query("SELECT * FROM rides WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<RideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ride: RideEntity): Long

    @Query(
        """
        UPDATE rides
        SET startPlaceName = :startPlaceName,
            endPlaceName = :endPlaceName
        WHERE id = :id
        """
    )
    suspend fun updatePlaceNames(id: Long, startPlaceName: String?, endPlaceName: String?)

    @Query("UPDATE rides SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String)

    @Query("UPDATE rides SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rides WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM rides")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(distanceKm), 0) FROM rides")
    fun observeTotalDistance(): Flow<Double>

    @Query(
        """
        SELECT AVG(approxKmPerLitre) FROM rides
        WHERE approxKmPerLitre IS NOT NULL AND approxKmPerLitre > 0
        """
    )
    fun observeAverageEconomy(): Flow<Double?>
}

@Dao
interface ParkedLocationDao {
    @Query("SELECT * FROM parked_locations ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 1): Flow<List<ParkedLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: ParkedLocationEntity): Long

    @Query("DELETE FROM parked_locations")
    suspend fun clearAll()

    @Query("DELETE FROM parked_locations WHERE id NOT IN (SELECT id FROM parked_locations ORDER BY timestampMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 1)
}

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY name ASC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlaceEntity): Long

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun delete(id: Long)
}
