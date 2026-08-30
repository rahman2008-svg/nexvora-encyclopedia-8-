package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ArticleEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.ReadingHistoryEntity
import com.example.data.model.ArticleDetail
import com.example.data.model.CategoryWithCount
import com.example.data.model.ContentStats
import com.example.util.BengaliTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar

class EncyclopediaRepository(
    private val database: AppDatabase,
    private val context: Context
) {
    private val articleDao = database.articleDao()
    private val categoryDao = database.categoryDao()
    private val searchDao = database.searchDao()
    private val bookmarkDao = database.bookmarkDao()
    private val statsDao = database.statsDao()

    fun getArticleDetail(articleId: String): Flow<ArticleDetail?> {
        val articleFlow = articleDao.getArticleById(articleId)
        val sectionsFlow = articleDao.getSectionsForArticle(articleId)
        val tagsFlow = articleDao.getTagsForArticle(articleId)
        val relatedFlow = articleDao.getRelatedArticles(articleId)
        val isBookmarkedFlow = bookmarkDao.isBookmarked(articleId)
        val translationFlow = articleDao.getTranslation(articleId, "en")
        val sectionTranslationsFlow = articleDao.getSectionTranslations(articleId, "en")

        return combine(
            articleFlow,
            sectionsFlow,
            tagsFlow,
            relatedFlow,
            isBookmarkedFlow,
            translationFlow,
            sectionTranslationsFlow
        ) { values ->
            val article = values[0] as? ArticleEntity
            val sections = values[1] as? List<com.example.data.local.entity.SectionEntity> ?: emptyList()
            val tags = values[2] as? List<com.example.data.local.entity.TagEntity> ?: emptyList()
            val related = values[3] as? List<ArticleEntity> ?: emptyList()
            val isBookmarked = values[4] as? Boolean ?: false
            val translation = values[5] as? com.example.data.local.entity.ArticleTranslationEntity
            val sectionTranslations = values[6] as? List<com.example.data.local.entity.SectionTranslationEntity> ?: emptyList()

            if (article == null) null
            else {
                val category = if (article.categoryId.isNotBlank()) {
                    categoryDao.getCategoryByIdDirect(article.categoryId)
                } else null

                val convertedEnglishSections = sectionTranslations.map { st ->
                    com.example.data.local.entity.SectionEntity(
                        id = st.id,
                        articleId = st.articleId,
                        title = st.title,
                        content = st.content,
                        position = st.position,
                        level = st.level
                    )
                }

                ArticleDetail(
                    article = article,
                    category = category,
                    sections = sections,
                    tags = tags,
                    relatedArticles = related,
                    isBookmarked = isBookmarked,
                    hasEnglishTranslation = (translation != null),
                    englishTitle = translation?.title,
                    englishSummary = translation?.summary,
                    englishContent = translation?.content,
                    englishSections = convertedEnglishSections
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getFeaturedArticles(limit: Int = 6): Flow<List<ArticleEntity>> {
        return articleDao.getFeaturedArticles(limit)
    }

    fun getArticlesByCategory(categoryId: String): Flow<List<ArticleEntity>> {
        return articleDao.getArticlesForCategory(categoryId)
    }

    suspend fun getRandomArticle(): ArticleEntity? {
        return withContext(Dispatchers.IO) {
            articleDao.getRandomArticle()
        }
    }

    /**
     * Deterministic daily article selection based on calendar day.
     * Guaranteed to produce the exact same article throughout the entire calendar day offline
     * with O(1) memory safety even with 100,000+ articles.
     */
     fun getDailyArticle(): Flow<ArticleEntity?> = flow {
         val totalCount = withContext(Dispatchers.IO) {
             articleDao.getTotalArticlesCountDirect()
         }
         if (totalCount <= 0) {
             emit(null)
         } else {
             val cal = Calendar.getInstance()
             val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
             val year = cal.get(Calendar.YEAR)
             val seed = (year * 366 + dayOfYear)
             val selectedIndex = kotlin.math.abs(seed) % totalCount
             val article = withContext(Dispatchers.IO) {
                 articleDao.getArticleByOffset(selectedIndex)
             }
             emit(article)
         }
     }.flowOn(Dispatchers.IO)

    fun searchArticles(query: String): Flow<List<ArticleEntity>> {
        val normalized = BengaliTextNormalizer.normalize(query)
        if (normalized.isBlank()) {
            return flow { emit(emptyList()) }
        }
        val ftsQuery = BengaliTextNormalizer.prepareFtsQuery(query)
        return flow {
            // First attempt FTS search, fallback to LIKE query
            try {
                if (ftsQuery.isNotBlank()) {
                    searchDao.searchArticlesFts(ftsQuery).collect { ftsResults ->
                        if (ftsResults.isNotEmpty()) {
                            emit(ftsResults)
                        } else {
                            searchDao.searchArticlesLike(normalized).collect { likeResults ->
                                emit(likeResults)
                            }
                        }
                    }
                } else {
                    searchDao.searchArticlesLike(normalized).collect { likeResults ->
                        emit(likeResults)
                    }
                }
            } catch (e: Exception) {
                // Fallback to LIKE on any FTS syntax edge case
                searchDao.searchArticlesLike(normalized).collect { likeResults ->
                    emit(likeResults)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getRootCategories(): Flow<List<CategoryEntity>> {
        return categoryDao.getRootCategories()
    }

    fun getAllCategories(): Flow<List<CategoryEntity>> {
        return categoryDao.getAllCategories()
    }

    fun getSubcategories(parentId: String): Flow<List<CategoryEntity>> {
        return categoryDao.getSubcategories(parentId)
    }

    fun getCategoryById(id: String): Flow<CategoryEntity?> {
        return categoryDao.getCategoryById(id)
    }

    fun getCategoryWithCounts(): Flow<List<CategoryWithCount>> = flow {
        try {
            categoryDao.getCategoriesWithCountsGrouped().collect { groupedList ->
                val result = groupedList.map { tuple ->
                    CategoryWithCount(
                        category = CategoryEntity(
                            id = tuple.id,
                            name = tuple.name,
                            parentId = tuple.parentId,
                            path = tuple.path,
                            depth = tuple.depth
                        ),
                        articleCount = tuple.articleCount
                    )
                }
                emit(result)
            }
        } catch (e: Exception) {
            categoryDao.getAllCategories().collect { categories ->
                val listWithCounts = withContext(Dispatchers.IO) {
                    categories.map { cat ->
                        val count = categoryDao.getArticleCountForCategory(cat.id)
                        CategoryWithCount(cat, count)
                    }
                }
                emit(listWithCounts)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getTotalCounts(): Flow<Pair<Int, Int>> {
        return combine(
            articleDao.getTotalArticlesCount(),
            categoryDao.getTotalCategoriesCount()
        ) { articles, categories ->
            Pair(articles, categories)
        }
    }

    fun getContentStats(): Flow<ContentStats> = flow {
        try {
            val inputStream = context.assets.open("content-stats.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonStr = reader.use { it.readText() }
            val json = JSONObject(jsonStr)
            val stats = ContentStats(
                version = json.optString("version", "1.0.0"),
                compiledAt = json.optLong("compiledAt", 0L),
                articles = json.optInt("articles", json.optInt("totalArticles", 0)),
                totalArticles = json.optInt("totalArticles", json.optInt("articles", 0)),
                categories = json.optInt("categories", json.optInt("totalCategories", 0)),
                totalCategories = json.optInt("totalCategories", json.optInt("categories", 0)),
                subcategories = json.optInt("subcategories", json.optInt("totalSubcategories", 0)),
                totalSubcategories = json.optInt("totalSubcategories", json.optInt("subcategories", 0)),
                sections = json.optInt("sections", json.optInt("totalSections", 0)),
                totalSections = json.optInt("totalSections", json.optInt("sections", 0)),
                tags = json.optInt("tags", json.optInt("totalTags", 0)),
                totalTags = json.optInt("totalTags", json.optInt("tags", 0)),
                relations = json.optInt("relations", json.optInt("totalRelations", 0)),
                totalRelations = json.optInt("totalRelations", json.optInt("relations", 0)),
                images = json.optInt("images", json.optInt("totalImages", 0)),
                totalImages = json.optInt("totalImages", json.optInt("images", 0)),
                bengaliArticles = json.optInt("bengaliArticles", json.optInt("articles", 0)),
                englishArticles = json.optInt("englishArticles", 0),
                articlesWithBothLanguages = json.optInt("articlesWithBothLanguages", 0),
                articlesWithoutEnglishTranslation = json.optInt("articlesWithoutEnglishTranslation", json.optInt("articles", 0)),
                databaseSizeBytes = json.optLong("databaseSizeBytes", 0L),
                databaseSizeFormatted = json.optString("databaseSizeFormatted", "0 KB"),
                averageArticleSizeChars = json.optInt("averageArticleSizeChars", 0),
                totalContentSizeChars = json.optLong("totalContentSizeChars", 0L),
                brokenInternalLinks = json.optInt("brokenInternalLinks", 0),
                ftsEnabled = json.optBoolean("ftsEnabled", true)
            )
            emit(stats)
        } catch (e: Exception) {
            val articleCount = withContext(Dispatchers.IO) { statsDao.getArticleCount() }
            val catCount = withContext(Dispatchers.IO) { statsDao.getCategoryCount() }
            val secCount = withContext(Dispatchers.IO) { statsDao.getSectionCount() }
            val tagCount = withContext(Dispatchers.IO) { statsDao.getTagCount() }
            val enCount = withContext(Dispatchers.IO) { statsDao.getEnglishTranslationCount() }
            emit(
                ContentStats(
                    version = "1.0.0",
                    articles = articleCount,
                    totalArticles = articleCount,
                    categories = catCount,
                    totalCategories = catCount,
                    sections = secCount,
                    totalSections = secCount,
                    tags = tagCount,
                    totalTags = tagCount,
                    bengaliArticles = articleCount,
                    englishArticles = enCount,
                    articlesWithBothLanguages = enCount,
                    articlesWithoutEnglishTranslation = (articleCount - enCount).coerceAtLeast(0)
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}

class BookmarkRepository(private val database: AppDatabase) {
    private val bookmarkDao = database.bookmarkDao()

    fun getBookmarkedArticles(): Flow<List<ArticleEntity>> = bookmarkDao.getBookmarkedArticles()

    suspend fun toggleBookmark(articleId: String, currentStatus: Boolean) {
        withContext(Dispatchers.IO) {
            if (currentStatus) {
                bookmarkDao.removeBookmark(articleId)
            } else {
                bookmarkDao.addBookmark(BookmarkEntity(articleId = articleId))
            }
        }
    }

    suspend fun clearAllBookmarks() {
        withContext(Dispatchers.IO) {
            bookmarkDao.clearAllBookmarks()
        }
    }
}

class HistoryRepository(private val database: AppDatabase) {
    private val historyDao = database.historyDao()

    fun getReadingHistory(): Flow<List<ArticleEntity>> = historyDao.getReadingHistory()

    suspend fun recordRead(articleId: String) {
        withContext(Dispatchers.IO) {
            historyDao.recordHistory(
                ReadingHistoryEntity(
                    articleId = articleId,
                    lastReadAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun clearHistory() {
        withContext(Dispatchers.IO) {
            historyDao.clearHistory()
        }
    }
}
