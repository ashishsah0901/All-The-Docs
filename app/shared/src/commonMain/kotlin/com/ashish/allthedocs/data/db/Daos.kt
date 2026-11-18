package com.ashish.allthedocs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    fun observe(id: String): Flow<ScenarioEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScenarioEntity)

    @Query("DELETE FROM scenarios WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScenarioDraftDao {
    @Query("SELECT * FROM scenario_drafts ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ScenarioDraftEntity>>

    @Query("SELECT * FROM scenario_drafts WHERE id = :id")
    suspend fun get(id: String): ScenarioDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScenarioDraftEntity)

    @Query("DELETE FROM scenario_drafts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface VaultDocumentDao {
    @Query("SELECT * FROM vault_documents ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<VaultDocumentEntity>>

    @Query("SELECT * FROM vault_documents WHERE id = :id")
    fun observe(id: String): Flow<VaultDocumentEntity?>

    @Query("SELECT * FROM vault_documents WHERE id = :id")
    suspend fun get(id: String): VaultDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VaultDocumentEntity)

    @Query("DELETE FROM vault_documents WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)
}
