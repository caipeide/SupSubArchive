package com.peide.supsub.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "export_settings")

class ExportSettings(private val context: Context) {

    private val dataStore = context.dataStore

    /**
     * 备份导出目录（SAF tree uri）。
     *
     * V2 起它**不再是数据存放地**——数据都在应用私有目录的 SQLite 单库里；
     * 这个目录只在用户点「导出备份」时用来落一份 .db 副本。
     */
    suspend fun exportDirUri(): Uri? = dataStore.data.map { prefs ->
        prefs[KEY_EXPORT_DIR]?.let { Uri.parse(it) }
    }.first()

    val exportDirFlow: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[KEY_EXPORT_DIR]?.let { Uri.parse(it) }
    }

    val notionTokenFlow: Flow<String?> = dataStore.data.map { it[KEY_NOTION_TOKEN] }

    /**
     * Notion **容器页** ID（按天子数据库都建在这个 page 下）。
     *
     * 兼容读取：上一版分库尝试写过 `shard_parent_page_id`，若新键为空则沿用它，
     * 免得已经配好的设备还要重填一次。
     */
    val notionParentPageIdFlow: Flow<String?> = dataStore.data.map {
        it[KEY_NOTION_PARENT_PAGE_ID] ?: it[KEY_LEGACY_SHARD_PARENT]
    }

    suspend fun notionParentPageId(): String? = notionParentPageIdFlow.first()

    /** 启用的订阅源类型（MP / WEBSITE / X）。默认只开 MP + WEBSITE，X 由用户手动开启。 */
    val enabledSourceTypesFlow: Flow<Set<String>> = dataStore.data.map {
        it[KEY_ENABLED_SOURCE_TYPES] ?: DEFAULT_ENABLED_SOURCE_TYPES
    }

    /** 是否在拉取结果里包含「关注点（focus）」内容。默认 false（不拉关注点）。 */
    val includeFocusFlow: Flow<Boolean> = dataStore.data.map {
        it[KEY_INCLUDE_FOCUS] ?: false
    }

    /**
     * 双向同步开关：同步完成后是否把 Notion 端较新的改动回拉本地（LWW）。
     * 默认 true 开启——用户明确希望「本地↔远端以最新编辑时间为准互相覆盖」。
     */
    val bidirectionalSyncFlow: Flow<Boolean> = dataStore.data.map {
        it[KEY_BIDIRECTIONAL_SYNC] ?: true
    }

    suspend fun setExportDir(uri: Uri) {
        dataStore.edit { it[KEY_EXPORT_DIR] = uri.toString() }
    }

    /** 保存 Notion 配置：Token + 容器页 ID */
    suspend fun setNotionConfig(token: String, parentPageId: String) {
        dataStore.edit {
            it[KEY_NOTION_TOKEN] = token
            it[KEY_NOTION_PARENT_PAGE_ID] = parentPageId
            // 同步写一份旧键，方便回滚到上一版 APK 时配置不丢
            it[KEY_LEGACY_SHARD_PARENT] = parentPageId
        }
    }

    /** 开启/关闭某个订阅源类型 */
    suspend fun setSourceTypeEnabled(type: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_ENABLED_SOURCE_TYPES] ?: DEFAULT_ENABLED_SOURCE_TYPES
            val next = if (enabled) current + type else current - type
            prefs[KEY_ENABLED_SOURCE_TYPES] = next
        }
    }

    /** 开启/关闭关注点内容拉取 */
    suspend fun setIncludeFocus(enabled: Boolean) {
        dataStore.edit { it[KEY_INCLUDE_FOCUS] = enabled }
    }

    /** 开启/关闭双向同步（拉取远端更新回本地） */
    suspend fun setBidirectionalSync(enabled: Boolean) {
        dataStore.edit { it[KEY_BIDIRECTIONAL_SYNC] = enabled }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_EXPORT_DIR = stringPreferencesKey("export_dir_uri")
        private val KEY_NOTION_TOKEN = stringPreferencesKey("notion_token")
        private val KEY_NOTION_PARENT_PAGE_ID = stringPreferencesKey("notion_parent_page_id")
        private val KEY_LEGACY_SHARD_PARENT = stringPreferencesKey("shard_parent_page_id")
        private val KEY_ENABLED_SOURCE_TYPES = stringSetPreferencesKey("enabled_source_types")
        private val KEY_INCLUDE_FOCUS = booleanPreferencesKey("include_focus")
        private val KEY_BIDIRECTIONAL_SYNC = booleanPreferencesKey("notion_bidirectional_sync")

        /** 默认只拉 公众号(MP) + 网站(WEBSITE)，不含 X，也不含关注点 */
        val DEFAULT_ENABLED_SOURCE_TYPES: Set<String> = setOf("MP", "WEBSITE")
    }
}
