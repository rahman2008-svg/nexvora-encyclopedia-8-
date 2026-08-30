package com.example.data.model

import com.example.data.local.entity.CategoryEntity

data class RemoteContentManifest(
    val schemaVersion: Int = 1,
    val contentVersion: Int = 1,
    val revision: String = "",
    val generatedAt: Long = 0L,
    val totalArticles: Int = 0,
    val defaultRepo: String = "",
    val categories: List<CategoryEntity> = emptyList(),
    val articles: Map<String, RemoteArticleItem> = emptyMap(),
    val contentPacks: List<ContentPackInfo> = emptyList(),
    val deletedArticleIds: List<String> = emptyList()
)

data class ContentPackManifest(
    val schemaVersion: Int = 1,
    val totalPacks: Int = 0,
    val totalArticles: Int = 0,
    val generatedAt: Long = 0L,
    val revision: String = "",
    val packs: List<ContentPackInfo> = emptyList()
)

data class ContentPackInfo(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val articleCount: Int = 0,
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val downloadUrl: String = ""
)

data class ContentPackPayload(
    val packId: String,
    val name: String = "",
    val categoryId: String? = null,
    val articleCount: Int = 0,
    val sha256: String? = null,
    val articles: List<ArticleUpdatePayload> = emptyList()
)

data class RemoteArticleItem(
    val id: String,
    val title: String,
    val slug: String,
    val categoryId: String,
    val contentHash: String,
    val updatedAt: Long,
    val hasEnglish: Boolean = false,
    val englishHash: String? = null,
    val updateUrl: String? = null,
    val rawPath: String? = null
)

data class ArticleUpdatePayload(
    val id: String,
    val title: String,
    val slug: String,
    val summary: String,
    val content: String,
    val categoryId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val contentHash: String,
    val sha256: String? = null,
    val category: CategoryEntity? = null,
    val sections: List<SectionPayload> = emptyList(),
    val tags: List<String> = emptyList(),
    val relations: List<RelationPayload> = emptyList(),
    val englishTranslation: TranslationPayload? = null
)

data class SectionPayload(
    val id: String,
    val articleId: String,
    val title: String,
    val content: String,
    val position: Int,
    val level: Int
)

data class RelationPayload(
    val id: String,
    val articleId: String,
    val relatedArticleId: String,
    val relationType: String
)

data class TranslationPayload(
    val title: String,
    val summary: String,
    val content: String,
    val updatedAt: Long,
    val sha256: String? = null,
    val sections: List<SectionTranslationPayload> = emptyList()
)

data class SectionTranslationPayload(
    val id: String,
    val articleId: String,
    val languageCode: String,
    val title: String,
    val content: String,
    val position: Int,
    val level: Int
)

sealed class SyncState {
    object Idle : SyncState()
    object Checking : SyncState()
    data class Syncing(
        val current: Int,
        val total: Int,
        val currentArticleTitle: String,
        val progress: Float
    ) : SyncState()
    data class Success(val updatedCount: Int, val message: String, val timestamp: Long) : SyncState()
    data class UpToDate(val message: String, val timestamp: Long) : SyncState()
    data class Error(val message: String, val timestamp: Long) : SyncState()
}

data class SyncReport(
    val totalChecked: Int,
    val newArticles: Int,
    val updatedArticles: Int,
    val updatedTranslations: Int,
    val deletedArticles: Int,
    val durationMs: Long
)
