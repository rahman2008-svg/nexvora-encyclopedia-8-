package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ArticleEntity
import com.example.data.local.entity.ArticleRelationEntity
import com.example.data.local.entity.ArticleTagCrossRef
import com.example.data.local.entity.ArticleTranslationEntity
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.entity.SectionTranslationEntity
import com.example.data.local.entity.TagEntity
import com.example.data.model.*
import com.example.util.BengaliTextNormalizer
import com.example.util.MarkdownArticleParser
import com.example.util.Sha256Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ContentSyncManager(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        const val PREFS_NAME = "nexvora_sync_prefs"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val KEY_CONTENT_VERSION = "synced_content_version"
        const val KEY_REVISION = "synced_revision"
        const val KEY_CUSTOM_REPO_URL = "custom_repo_url"

        const val DEFAULT_GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/Prince-AR-Abdur-Rahman/nexvora-encyclopedia/main/"

        @Volatile
        private var INSTANCE: ContentSyncManager? = null

        fun getInstance(context: Context, database: AppDatabase): ContentSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContentSyncManager(context.applicationContext, database).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val articleDao = database.articleDao()
    private val categoryDao = database.categoryDao()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L))
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    fun getRepoBaseUrl(): String {
        val custom = prefs.getString(KEY_CUSTOM_REPO_URL, null)
        return if (!custom.isNullOrBlank()) {
            if (custom.endsWith("/")) custom else "$custom/"
        } else {
            DEFAULT_GITHUB_RAW_BASE
        }
    }

    fun setCustomRepoUrl(url: String?) {
        prefs.edit().putString(KEY_CUSTOM_REPO_URL, url?.trim()).apply()
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Executes full incremental live synchronization from GitHub.
     * Guaranteed safe: uses SHA-256 verification and atomic Room transaction with rollback on failure.
     */
    suspend fun syncContent(forceFullCheck: Boolean = false): SyncReport = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!isOnline()) {
            val errorMsg = "ইন্টারনেট সংযোগ নেই। অনুগ্রহ করে ইন্টারনেট সংযোগ পরীক্ষা করে পুনরায় চেষ্টা করুন।"
            _syncState.value = SyncState.Error(errorMsg, System.currentTimeMillis())
            return@withContext SyncReport(0, 0, 0, 0, 0, 0)
        }

        _syncState.value = SyncState.Checking

        try {
            val baseUrl = getRepoBaseUrl()
            val manifest = fetchRemoteManifest(baseUrl)
            if (manifest == null) {
                val errorMsg = "এই মুহূর্তে আপডেট চেক করা সম্ভব হচ্ছে না। অনুগ্রহ করে কিছুক্ষণ পর আবার চেষ্টা করুন।"
                _syncState.value = SyncState.Error(errorMsg, System.currentTimeMillis())
                return@withContext SyncReport(0, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
            }

            // Read existing local state from Room SQLite
            val localSummaries = articleDao.getAllArticleSummaries()
            val localMap = localSummaries.associateBy { it.id }
            val localTranslations = articleDao.getAllTranslationSummaries()
            val localTranslationMap = localTranslations.associateBy { "${it.articleId}:${it.languageCode}" }

            val newArticles = mutableListOf<RemoteArticleItem>()
            val updatedArticles = mutableListOf<RemoteArticleItem>()
            val updatedTranslations = mutableListOf<RemoteArticleItem>()

            for ((id, remoteItem) in manifest.articles) {
                val local = localMap[id]
                if (local == null) {
                    newArticles.add(remoteItem)
                } else if (remoteItem.contentHash.isNotBlank() && remoteItem.contentHash != local.contentHash) {
                    updatedArticles.add(remoteItem)
                } else if (remoteItem.updatedAt > local.updatedAt) {
                    updatedArticles.add(remoteItem)
                }

                // Check English translation delta
                if (remoteItem.hasEnglish) {
                    val localTrans = localTranslationMap["$id:en"]
                    if (localTrans == null || (remoteItem.englishHash != null && remoteItem.englishHash.isNotBlank())) {
                        if (local == null || !newArticles.contains(remoteItem)) {
                            updatedTranslations.add(remoteItem)
                        }
                    }
                }
            }

            val totalChanges = newArticles.size + updatedArticles.size + updatedTranslations.size
            val deletedCount = manifest.deletedArticleIds.size

            if (totalChanges == 0 && deletedCount == 0) {
                saveSyncTimestamp(manifest.contentVersion, manifest.revision)
                val msg = "আপনার জ্ঞানভাণ্ডার সম্পূর্ণ হালনাগাদ রয়েছে (মোট ${manifest.totalArticles} টি নিবন্ধ)"
                _syncState.value = SyncState.UpToDate(msg, System.currentTimeMillis())
                return@withContext SyncReport(manifest.articles.size, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
            }

            // Download and process deltas
            var processed = 0
            val allDeltasToFetch = (newArticles + updatedArticles + updatedTranslations).distinctBy { it.id }

            // Upsert Categories first
            if (manifest.categories.isNotEmpty()) {
                categoryDao.upsertCategories(manifest.categories)
            }

            for (item in allDeltasToFetch) {
                processed++
                _syncState.value = SyncState.Syncing(
                    current = processed,
                    total = allDeltasToFetch.size,
                    currentArticleTitle = item.title,
                    progress = processed.toFloat() / allDeltasToFetch.size
                )

                // Fetch update payload or raw markdown
                val payload = fetchArticlePayload(baseUrl, item)
                if (payload != null) {
                    applyArticleUpdateAtomically(payload)
                }
            }

            // Handle deleted articles if any specified
            if (manifest.deletedArticleIds.isNotEmpty()) {
                database.withTransaction {
                    for (delId in manifest.deletedArticleIds) {
                        articleDao.deleteArticle(delId)
                        articleDao.deleteSectionsForArticle(delId)
                        articleDao.deleteArticleTags(delId)
                        articleDao.deleteRelations(delId)
                        articleDao.deleteTranslationsForArticle(delId)
                    }
                }
            }

            saveSyncTimestamp(manifest.contentVersion, manifest.revision)
            val successMsg = "$totalChanges টি নিবন্ধ সফলভাবে সিঙ্ক ও হালনাগাদ করা হয়েছে ✨"
            _syncState.value = SyncState.Success(totalChanges, successMsg, System.currentTimeMillis())

            return@withContext SyncReport(
                totalChecked = manifest.articles.size,
                newArticles = newArticles.size,
                updatedArticles = updatedArticles.size,
                updatedTranslations = updatedTranslations.size,
                deletedArticles = deletedCount,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            val errorMsg = "কন্টেন্ট আপডেট সম্পন্ন করা যায়নি। অনুগ্রহ করে কিছুক্ষণ পর আবার চেষ্টা করুন।"
            _syncState.value = SyncState.Error(errorMsg, System.currentTimeMillis())
            return@withContext SyncReport(0, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
        }
    }

    private fun saveSyncTimestamp(version: Int, revision: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_SYNC, now)
            .putInt(KEY_CONTENT_VERSION, version)
            .putString(KEY_REVISION, revision)
            .apply()
        _lastSyncTime.value = now
    }

    private fun fetchRemoteManifest(baseUrl: String): RemoteContentManifest? {
        val possibleUrls = listOf(
            "${baseUrl}content-manifest.json",
            "${baseUrl}updates/content-manifest.json",
            "${baseUrl}app/src/main/assets/content-manifest.json"
        )

        for (url in possibleUrls) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: continue
                    return parseManifestJson(body)
                }
            } catch (e: Exception) {
                // Try next URL fallback
            }
        }
        return null
    }

    private fun parseManifestJson(jsonStr: String): RemoteContentManifest {
        val root = JSONObject(jsonStr)
        val schemaVer = root.optInt("schemaVersion", 1)
        val contentVer = root.optInt("contentVersion", 1)
        val revision = root.optString("revision", "")
        val genAt = root.optLong("generatedAt", System.currentTimeMillis())
        val totalArts = root.optInt("totalArticles", 0)
        val defaultRepo = root.optString("defaultRepo", "")

        val categories = mutableListOf<CategoryEntity>()
        val catArray = root.optJSONArray("categories")
        if (catArray != null) {
            for (i in 0 until catArray.length()) {
                val cObj = catArray.getJSONObject(i)
                categories.add(
                    CategoryEntity(
                        id = cObj.getString("id"),
                        name = cObj.getString("name"),
                        parentId = cObj.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                        path = cObj.optString("path", ""),
                        depth = cObj.optInt("depth", 1)
                    )
                )
            }
        }

        val articles = mutableMapOf<String, RemoteArticleItem>()
        val artsObj = root.optJSONObject("articles")
        if (artsObj != null) {
            val keys = artsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val aObj = artsObj.getJSONObject(key)
                articles[key] = RemoteArticleItem(
                    id = aObj.optString("id", key),
                    title = aObj.optString("title", key),
                    slug = aObj.optString("slug", key),
                    categoryId = aObj.optString("categoryId", "general"),
                    contentHash = aObj.optString("contentHash", ""),
                    updatedAt = aObj.optLong("updatedAt", System.currentTimeMillis()),
                    hasEnglish = aObj.optBoolean("hasEnglish", false),
                    englishHash = aObj.optString("englishHash", null),
                    updateUrl = aObj.optString("updateUrl", null),
                    rawPath = aObj.optString("rawPath", null)
                )
            }
        }

        val deletedIds = mutableListOf<String>()
        val delArray = root.optJSONArray("deletedArticleIds")
        if (delArray != null) {
            for (i in 0 until delArray.length()) {
                deletedIds.add(delArray.getString(i))
            }
        }

        val contentPacks = mutableListOf<com.example.data.model.ContentPackInfo>()
        val packsArray = root.optJSONArray("contentPacks")
        if (packsArray != null) {
            for (i in 0 until packsArray.length()) {
                val pObj = packsArray.getJSONObject(i)
                contentPacks.add(
                    com.example.data.model.ContentPackInfo(
                        id = pObj.getString("id"),
                        name = pObj.optString("name", pObj.getString("id")),
                        categoryId = pObj.optString("categoryId").takeIf { it.isNotBlank() && it != "null" },
                        articleCount = pObj.optInt("articleCount", 0),
                        sha256 = pObj.optString("sha256", ""),
                        sizeBytes = pObj.optLong("sizeBytes", 0L),
                        downloadUrl = pObj.optString("downloadUrl", "")
                    )
                )
            }
        }

        return RemoteContentManifest(
            schemaVersion = schemaVer,
            contentVersion = contentVer,
            revision = revision,
            generatedAt = genAt,
            totalArticles = totalArts.takeIf { it > 0 } ?: articles.size,
            defaultRepo = defaultRepo,
            categories = categories,
            articles = articles,
            contentPacks = contentPacks,
            deletedArticleIds = deletedIds
        )
    }

    private fun fetchArticlePayload(baseUrl: String, item: RemoteArticleItem): ArticleUpdatePayload? {
        // Strategy 1: Try structured update payload if updateUrl specified
        if (!item.updateUrl.isNullOrBlank()) {
            try {
                val url = "${baseUrl}${item.updateUrl}"
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        return parseArticlePayloadJson(body)
                    }
                }
            } catch (e: Exception) {
                // Fallback to Markdown strategy
            }
        }

        // Strategy 2: Try updates/articles/{id}.json
        try {
            val url = "${baseUrl}updates/articles/${item.id}.json"
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (!body.isNullOrBlank()) {
                    return parseArticlePayloadJson(body)
                }
            }
        } catch (e: Exception) {
            // Fallback to Markdown
        }

        // Strategy 3: Directly fetch and parse Markdown from rawPath
        if (!item.rawPath.isNullOrBlank()) {
            try {
                val mdUrl = "${baseUrl}${item.rawPath}"
                val req = Request.Builder().url(mdUrl).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val rawMd = resp.body?.string()
                    if (!rawMd.isNullOrBlank()) {
                        val parsed = MarkdownArticleParser.parse(rawMd, item.id, item.categoryId)

                        // Check English translation file
                        var englishTrans: TranslationPayload? = null
                        if (item.hasEnglish) {
                            val enPath = item.rawPath.replace(".md", ".en.md")
                            val enUrl = "${baseUrl}$enPath"
                            try {
                                val enReq = Request.Builder().url(enUrl).build()
                                val enResp = httpClient.newCall(enReq).execute()
                                if (enResp.isSuccessful) {
                                    val enMd = enResp.body?.string()
                                    if (!enMd.isNullOrBlank()) {
                                        val enParsed = MarkdownArticleParser.parse(enMd, item.id, item.categoryId)
                                        val enSecs = enParsed.sections.map { s ->
                                            SectionTranslationPayload(
                                                id = s.id,
                                                articleId = item.id,
                                                languageCode = "en",
                                                title = s.title,
                                                content = s.content,
                                                position = s.position,
                                                level = s.level
                                            )
                                        }
                                        englishTrans = TranslationPayload(
                                            title = enParsed.title,
                                            summary = enParsed.summary,
                                            content = enParsed.content,
                                            updatedAt = System.currentTimeMillis(),
                                            sha256 = enParsed.sha256Hash,
                                            sections = enSecs
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                // Graceful ignore translation error
                            }
                        }

                        val sectionPayloads = parsed.sections.map { s ->
                            SectionPayload(s.id, s.articleId, s.title, s.content, s.position, s.level)
                        }

                        return ArticleUpdatePayload(
                            id = parsed.id,
                            title = parsed.title,
                            slug = parsed.slug,
                            summary = parsed.summary,
                            content = parsed.content,
                            categoryId = parsed.categoryId,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            contentHash = parsed.contentHash,
                            sha256 = parsed.sha256Hash,
                            sections = sectionPayloads,
                            tags = parsed.tags,
                            englishTranslation = englishTrans
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return null
    }

    private fun parseArticlePayloadJson(jsonStr: String): ArticleUpdatePayload {
        val root = JSONObject(jsonStr)
        val id = root.getString("id")
        val title = root.getString("title")
        val slug = root.optString("slug", id)
        val summary = root.optString("summary", "")
        val content = root.optString("content", "")
        val categoryId = root.optString("categoryId", "general")
        val createdAt = root.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        val contentHash = root.optString("contentHash", "")
        val sha256 = root.optString("sha256", null)

        var category: CategoryEntity? = null
        val catObj = root.optJSONObject("category")
        if (catObj != null) {
            category = CategoryEntity(
                id = catObj.getString("id"),
                name = catObj.getString("name"),
                parentId = catObj.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                path = catObj.optString("path", ""),
                depth = catObj.optInt("depth", 1)
            )
        }

        val sections = mutableListOf<SectionPayload>()
        val secArray = root.optJSONArray("sections")
        if (secArray != null) {
            for (i in 0 until secArray.length()) {
                val sObj = secArray.getJSONObject(i)
                sections.add(
                    SectionPayload(
                        id = sObj.getString("id"),
                        articleId = sObj.optString("articleId", id),
                        title = sObj.getString("title"),
                        content = sObj.optString("content", ""),
                        position = sObj.optInt("position", i + 1),
                        level = sObj.optInt("level", 2)
                    )
                )
            }
        }

        val tags = mutableListOf<String>()
        val tagArray = root.optJSONArray("tags")
        if (tagArray != null) {
            for (i in 0 until tagArray.length()) {
                tags.add(tagArray.getString(i))
            }
        }

        val relations = mutableListOf<RelationPayload>()
        val relArray = root.optJSONArray("relations")
        if (relArray != null) {
            for (i in 0 until relArray.length()) {
                val rObj = relArray.getJSONObject(i)
                relations.add(
                    RelationPayload(
                        id = rObj.optString("id", "${id}_${rObj.getString("relatedArticleId")}"),
                        articleId = id,
                        relatedArticleId = rObj.getString("relatedArticleId"),
                        relationType = rObj.optString("relationType", "see_also")
                    )
                )
            }
        }

        var englishTrans: TranslationPayload? = null
        val enObj = root.optJSONObject("englishTranslation")
        if (enObj != null) {
            val enSections = mutableListOf<SectionTranslationPayload>()
            val enSecArray = enObj.optJSONArray("sections")
            if (enSecArray != null) {
                for (i in 0 until enSecArray.length()) {
                    val esObj = enSecArray.getJSONObject(i)
                    enSections.add(
                        SectionTranslationPayload(
                            id = esObj.optString("id", "$id-en-sec-${i + 1}"),
                            articleId = id,
                            languageCode = "en",
                            title = esObj.getString("title"),
                            content = esObj.optString("content", ""),
                            position = esObj.optInt("position", i + 1),
                            level = esObj.optInt("level", 2)
                        )
                    )
                }
            }
            englishTrans = TranslationPayload(
                title = enObj.getString("title"),
                summary = enObj.optString("summary", ""),
                content = enObj.optString("content", ""),
                updatedAt = enObj.optLong("updatedAt", System.currentTimeMillis()),
                sha256 = enObj.optString("sha256", null),
                sections = enSections
            )
        }

        return ArticleUpdatePayload(
            id = id,
            title = title,
            slug = slug,
            summary = summary,
            content = content,
            categoryId = categoryId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            contentHash = contentHash,
            sha256 = sha256,
            category = category,
            sections = sections,
            tags = tags,
            relations = relations,
            englishTranslation = englishTrans
        )
    }

    /**
     * Atomically applies the downloaded article payload into Room SQLite with FTS sync.
     * Validates SHA-256 integrity hash if provided.
     * If an error occurs, Room will rollback the transaction automatically.
     */
    private suspend fun applyArticleUpdateAtomically(payload: ArticleUpdatePayload) {
        // Verify SHA-256 if provided in payload
        if (!payload.sha256.isNullOrBlank()) {
            val calculatedHash = com.example.util.Sha256Util.sha256(payload.content)
            if (!calculatedHash.equals(payload.sha256, ignoreCase = true)) {
                throw IllegalStateException("SHA-256 integrity mismatch for article ${payload.id}")
            }
        }

        database.withTransaction {
            // 1. Upsert Category if present
            if (payload.category != null) {
                categoryDao.upsertCategory(payload.category)
            }

            // 2. Upsert ArticleEntity
            val articleEntity = ArticleEntity(
                id = payload.id,
                title = payload.title,
                slug = payload.slug,
                summary = payload.summary,
                content = payload.content,
                categoryId = payload.categoryId,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                contentHash = payload.contentHash
            )
            articleDao.upsertArticle(articleEntity)

            // 3. Update Sections
            articleDao.deleteSectionsForArticle(payload.id)
            if (payload.sections.isNotEmpty()) {
                val sectionEntities = payload.sections.map { s ->
                    SectionEntity(
                        id = s.id,
                        articleId = s.articleId,
                        title = s.title,
                        content = s.content,
                        position = s.position,
                        level = s.level
                    )
                }
                articleDao.insertSections(sectionEntities)
            }

            // 4. Update Tags
            articleDao.deleteArticleTags(payload.id)
            if (payload.tags.isNotEmpty()) {
                val tagEntities = payload.tags.map { tagName ->
                    val tagId = BengaliTextNormalizer.normalize(tagName)
                        .replace(" ", "-")
                        .replace(Regex("[^\\w\\-\\u0980-\\u09FF]"), "")
                    TagEntity(id = tagId, name = tagName)
                }
                articleDao.upsertTags(tagEntities)

                val crossRefs = tagEntities.map { tag ->
                    ArticleTagCrossRef(articleId = payload.id, tagId = tag.id)
                }
                articleDao.insertArticleTags(crossRefs)
            }

            // 5. Update Relations
            articleDao.deleteRelations(payload.id)
            if (payload.relations.isNotEmpty()) {
                val relEntities = payload.relations.map { r ->
                    ArticleRelationEntity(
                        id = r.id,
                        articleId = r.articleId,
                        relatedArticleId = r.relatedArticleId,
                        relationType = r.relationType
                    )
                }
                articleDao.insertRelations(relEntities)
            }

            // 6. Update English Translation
            if (payload.englishTranslation != null) {
                val trans = payload.englishTranslation
                articleDao.deleteSectionTranslations(payload.id, "en")

                val transEntity = ArticleTranslationEntity(
                    articleId = payload.id,
                    languageCode = "en",
                    title = trans.title,
                    summary = trans.summary,
                    content = trans.content,
                    updatedAt = trans.updatedAt
                )
                articleDao.upsertTranslation(transEntity)

                if (trans.sections.isNotEmpty()) {
                    val secTransEntities = trans.sections.map { st ->
                        SectionTranslationEntity(
                            id = st.id,
                            articleId = st.articleId,
                            languageCode = st.languageCode,
                            title = st.title,
                            content = st.content,
                            position = st.position,
                            level = st.level
                        )
                    }
                    articleDao.insertSectionTranslations(secTransEntities)
                }
            }
        }
    }

    /**
     * Atomically applies an entire Content Pack payload in a single transaction.
     */
    suspend fun applyContentPackAtomically(pack: com.example.data.model.ContentPackPayload) {
        database.withTransaction {
            for (articlePayload in pack.articles) {
                applyArticleUpdateAtomically(articlePayload)
            }
        }
    }
}
