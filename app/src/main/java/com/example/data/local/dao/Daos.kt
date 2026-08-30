package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ArticleEntity
import com.example.data.local.entity.ArticleRelationEntity
import com.example.data.local.entity.ArticleTagCrossRef
import com.example.data.local.entity.ArticleTranslationEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.ReadingHistoryEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.entity.SectionTranslationEntity
import com.example.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

data class ArticleSummaryTuple(
    val id: String,
    val contentHash: String,
    val updatedAt: Long
)

data class TranslationSummaryTuple(
    val articleId: String,
    val languageCode: String,
    val updatedAt: Long
)

data class CategoryWithCountTuple(
    val id: String,
    val name: String,
    val parentId: String?,
    val path: String,
    val depth: Int,
    val articleCount: Int
)

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    fun getArticleById(id: String): Flow<ArticleEntity?>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun getArticleByIdDirect(id: String): ArticleEntity?

    @Query("SELECT * FROM article_translations WHERE articleId = :articleId AND languageCode = :langCode LIMIT 1")
    fun getTranslation(articleId: String, langCode: String): Flow<ArticleTranslationEntity?>

    @Query("SELECT * FROM article_translations WHERE articleId = :articleId AND languageCode = :langCode LIMIT 1")
    suspend fun getTranslationDirect(articleId: String, langCode: String): ArticleTranslationEntity?

    @Query("SELECT * FROM section_translations WHERE articleId = :articleId AND languageCode = :langCode ORDER BY position ASC")
    fun getSectionTranslations(articleId: String, langCode: String): Flow<List<SectionTranslationEntity>>

    @Query("SELECT languageCode FROM article_translations WHERE articleId = :articleId")
    fun getAvailableLanguagesForArticle(articleId: String): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM article_translations WHERE articleId = :articleId AND languageCode = :langCode)")
    suspend fun hasTranslation(articleId: String, langCode: String): Boolean

    @Query("SELECT * FROM sections WHERE articleId = :articleId ORDER BY position ASC")
    fun getSectionsForArticle(articleId: String): Flow<List<SectionEntity>>

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN article_tags at ON t.id = at.tagId
        WHERE at.articleId = :articleId
    """)
    fun getTagsForArticle(articleId: String): Flow<List<TagEntity>>

    @Query("""
        SELECT a.* FROM articles a
        INNER JOIN article_relations ar ON a.id = ar.relatedArticleId
        WHERE ar.articleId = :articleId
    """)
    fun getRelatedArticles(articleId: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE categoryId = :categoryId ORDER BY title ASC")
    fun getArticlesForCategory(categoryId: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY title ASC")
    fun getAllArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY title ASC LIMIT :limit OFFSET :offset")
    fun getArticlesPaged(limit: Int, offset: Int): Flow<List<ArticleEntity>>

    @Query("SELECT COUNT(*) FROM articles")
    fun getTotalArticlesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getTotalArticlesCountDirect(): Int

    @Query("SELECT * FROM articles ORDER BY id ASC LIMIT 1 OFFSET :offset")
    suspend fun getArticleByOffset(offset: Int): ArticleEntity?

    @Query("SELECT * FROM articles ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomArticle(): ArticleEntity?

    @Query("SELECT * FROM articles ORDER BY id ASC LIMIT :limit")
    fun getFeaturedArticles(limit: Int = 6): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    fun getArticlesByIds(ids: List<String>): Flow<List<ArticleEntity>>

    // Live Sync Queries
    @Query("SELECT id, contentHash, updatedAt FROM articles")
    suspend fun getAllArticleSummaries(): List<ArticleSummaryTuple>

    @Query("SELECT articleId, languageCode, updatedAt FROM article_translations")
    suspend fun getAllTranslationSummaries(): List<TranslationSummaryTuple>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticle(article: ArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticles(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteArticle(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<SectionEntity>)

    @Query("DELETE FROM sections WHERE articleId = :articleId")
    suspend fun deleteSectionsForArticle(articleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslation(translation: ArticleTranslationEntity)

    @Query("DELETE FROM article_translations WHERE articleId = :articleId")
    suspend fun deleteTranslationsForArticle(articleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSectionTranslations(sections: List<SectionTranslationEntity>)

    @Query("DELETE FROM section_translations WHERE articleId = :articleId AND languageCode = :langCode")
    suspend fun deleteSectionTranslations(articleId: String, langCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticleTags(articleTags: List<ArticleTagCrossRef>)

    @Query("DELETE FROM article_tags WHERE articleId = :articleId")
    suspend fun deleteArticleTags(articleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<ArticleRelationEntity>)

    @Query("DELETE FROM article_relations WHERE articleId = :articleId")
    suspend fun deleteRelations(articleId: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId IS NULL OR parentId = '' ORDER BY name ASC")
    fun getRootCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY name ASC")
    fun getSubcategories(parentId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    fun getCategoryById(id: String): Flow<CategoryEntity?>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryByIdDirect(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    fun getTotalCategoriesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles WHERE categoryId = :categoryId")
    suspend fun getArticleCountForCategory(categoryId: String): Int

    @Query("""
        SELECT c.id, c.name, c.parentId, c.path, c.depth, COUNT(a.id) as articleCount
        FROM categories c
        LEFT JOIN articles a ON c.id = a.categoryId
        GROUP BY c.id
        ORDER BY c.name ASC
    """)
    fun getCategoriesWithCountsGrouped(): Flow<List<CategoryWithCountTuple>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)
}

@Dao
interface SearchDao {
    @Query("""
        SELECT a.* FROM articles a
        JOIN articles_fts f ON a.id = f.id
        WHERE articles_fts MATCH :query
        LIMIT :limit OFFSET :offset
    """)
    fun searchArticlesFtsPaged(query: String, limit: Int = 50, offset: Int = 0): Flow<List<ArticleEntity>>

    @Query("""
        SELECT a.* FROM articles a
        JOIN articles_fts f ON a.id = f.id
        WHERE articles_fts MATCH :query
        LIMIT 50
    """)
    fun searchArticlesFts(query: String): Flow<List<ArticleEntity>>

    @Query("""
        SELECT DISTINCT a.* FROM articles a
        LEFT JOIN article_translations at ON a.id = at.articleId
        LEFT JOIN article_tags tg ON a.id = tg.articleId
        LEFT JOIN tags t ON tg.tagId = t.id
        WHERE a.title LIKE '%' || :query || '%'
           OR a.summary LIKE '%' || :query || '%'
           OR a.content LIKE '%' || :query || '%'
           OR at.title LIKE '%' || :query || '%'
           OR at.summary LIKE '%' || :query || '%'
           OR at.content LIKE '%' || :query || '%'
           OR t.name LIKE '%' || :query || '%'
        ORDER BY 
           CASE WHEN a.title LIKE :query || '%' THEN 1
                WHEN at.title LIKE :query || '%' THEN 2
                WHEN a.title LIKE '%' || :query || '%' THEN 3
                ELSE 4 END,
           a.title ASC
        LIMIT :limit OFFSET :offset
    """)
    fun searchArticlesLikePaged(query: String, limit: Int = 50, offset: Int = 0): Flow<List<ArticleEntity>>

    @Query("""
        SELECT DISTINCT a.* FROM articles a
        LEFT JOIN article_translations at ON a.id = at.articleId
        LEFT JOIN article_tags tg ON a.id = tg.articleId
        LEFT JOIN tags t ON tg.tagId = t.id
        WHERE a.title LIKE '%' || :query || '%'
           OR a.summary LIKE '%' || :query || '%'
           OR a.content LIKE '%' || :query || '%'
           OR at.title LIKE '%' || :query || '%'
           OR at.summary LIKE '%' || :query || '%'
           OR at.content LIKE '%' || :query || '%'
           OR t.name LIKE '%' || :query || '%'
        ORDER BY 
           CASE WHEN a.title LIKE :query || '%' THEN 1
                WHEN at.title LIKE :query || '%' THEN 2
                WHEN a.title LIKE '%' || :query || '%' THEN 3
                ELSE 4 END,
           a.title ASC
        LIMIT 50
    """)
    fun searchArticlesLike(query: String): Flow<List<ArticleEntity>>
}

@Dao
interface BookmarkDao {
    @Query("""
        SELECT a.* FROM articles a
        INNER JOIN bookmarks b ON a.id = b.articleId
        ORDER BY b.createdAt DESC
    """)
    fun getBookmarkedArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE articleId = :articleId)")
    fun isBookmarked(articleId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE articleId = :articleId")
    suspend fun removeBookmark(articleId: String)

    @Query("DELETE FROM bookmarks")
    suspend fun clearAllBookmarks()
}

@Dao
interface HistoryDao {
    @Query("""
        SELECT a.* FROM articles a
        INNER JOIN reading_history h ON a.id = h.articleId
        ORDER BY h.lastReadAt DESC
        LIMIT :limit
    """)
    fun getReadingHistory(limit: Int = 50): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordHistory(history: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history")
    suspend fun clearHistory()
}

@Dao
interface StatsDao {
    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getArticleCount(): Int

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCategoryCount(): Int

    @Query("SELECT COUNT(*) FROM sections")
    suspend fun getSectionCount(): Int

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getTagCount(): Int

    @Query("SELECT COUNT(*) FROM article_translations WHERE languageCode = 'en'")
    suspend fun getEnglishTranslationCount(): Int

    @Query("SELECT COUNT(*) FROM bookmarks")
    fun getBookmarkCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_history")
    fun getHistoryCount(): Flow<Int>
}
