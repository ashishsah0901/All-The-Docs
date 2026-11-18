package com.ashish.allthedocs.data

import com.ashish.allthedocs.data.db.SettingDao
import com.ashish.allthedocs.data.db.SettingEntity
import com.ashish.allthedocs.platform.defaultServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dao: SettingDao) {
    val serverUrl: Flow<String> = dao.observe(KEY_SERVER_URL).map { it ?: defaultServerUrl() }
    val holderName: Flow<String> = dao.observe(KEY_HOLDER_NAME).map { it.orEmpty() }

    suspend fun serverUrl(): String = dao.get(KEY_SERVER_URL) ?: defaultServerUrl()

    suspend fun holderName(): String? = dao.get(KEY_HOLDER_NAME)?.takeIf { it.isNotBlank() }

    suspend fun setServerUrl(value: String) = dao.upsert(SettingEntity(KEY_SERVER_URL, value.trim()))

    suspend fun setHolderName(value: String) = dao.upsert(SettingEntity(KEY_HOLDER_NAME, value.trim()))

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_HOLDER_NAME = "holder_name"
    }
}
