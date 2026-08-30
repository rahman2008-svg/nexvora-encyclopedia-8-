package com.example.data.model

import com.example.data.local.entity.ArticleEntity
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.entity.TagEntity

enum class AppLanguage(val label: String, val code: String) {
    BENGALI("বাংলা", "bn"),
    ENGLISH("English", "en")
}

data class ArticleDetail(
    val article: ArticleEntity,
    val category: CategoryEntity?,
    val sections: List<SectionEntity>,
    val tags: List<TagEntity>,
    val relatedArticles: List<ArticleEntity>,
    val isBookmarked: Boolean,
    val hasEnglishTranslation: Boolean = false,
    val englishTitle: String? = null,
    val englishSummary: String? = null,
    val englishContent: String? = null,
    val englishSections: List<SectionEntity> = emptyList()
)

data class CategoryWithCount(
    val category: CategoryEntity,
    val articleCount: Int
)

data class ContentStats(
    val version: String = "1.0.0",
    val compiledAt: Long = 0L,
    val articles: Int = 0,
    val totalArticles: Int = 0,
    val categories: Int = 0,
    val totalCategories: Int = 0,
    val subcategories: Int = 0,
    val totalSubcategories: Int = 0,
    val sections: Int = 0,
    val totalSections: Int = 0,
    val tags: Int = 0,
    val totalTags: Int = 0,
    val relations: Int = 0,
    val totalRelations: Int = 0,
    val images: Int = 0,
    val totalImages: Int = 0,
    val bengaliArticles: Int = 0,
    val englishArticles: Int = 0,
    val articlesWithBothLanguages: Int = 0,
    val articlesWithoutEnglishTranslation: Int = 0,
    val databaseSizeBytes: Long = 0L,
    val databaseSizeFormatted: String = "0 KB",
    val averageArticleSizeChars: Int = 0,
    val totalContentSizeChars: Long = 0L,
    val brokenInternalLinks: Int = 0,
    val ftsEnabled: Boolean = true
)

data class TableOfContentsItem(
    val id: String,
    val title: String,
    val level: Int,
    val position: Int
)

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class ReaderFontSize(val titleBn: String, val titleEn: String, val scaleFactor: Float) {
    SMALL("ছোট", "Small", 0.9f),
    MEDIUM("স্বাভাবিক", "Medium", 1.0f),
    LARGE("বড়", "Large", 1.15f),
    EXTRA_LARGE("অনেক বড়", "Extra Large", 1.3f)
}
