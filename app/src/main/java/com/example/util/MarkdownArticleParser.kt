package com.example.util

import com.example.data.local.entity.ArticleEntity
import com.example.data.local.entity.ArticleTranslationEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.entity.SectionTranslationEntity

data class ParsedMarkdownArticle(
    val id: String,
    val title: String,
    val slug: String,
    val summary: String,
    val content: String,
    val categoryId: String,
    val tags: List<String>,
    val sections: List<SectionEntity>,
    val isTranslation: Boolean,
    val canonicalId: String,
    val languageCode: String,
    val contentHash: String,
    val sha256Hash: String
)

object MarkdownArticleParser {

    fun parse(
        rawContent: String,
        fallbackId: String,
        fallbackCategoryId: String = "general"
    ): ParsedMarkdownArticle {
        val (frontMatter, body) = parseFrontMatter(rawContent)

        val id = frontMatter["id"]?.takeIf { it.isNotBlank() }
            ?: frontMatter["canonical_id"]?.takeIf { it.isNotBlank() }
            ?: fallbackId

        val languageCode = frontMatter["language"]?.takeIf { it.isNotBlank() } ?: "bn"
        val isTranslation = languageCode != "bn" || frontMatter.containsKey("canonical_id")
        val canonicalId = frontMatter["canonical_id"]?.takeIf { it.isNotBlank() } ?: id

        val rawTitle = frontMatter["title"]?.takeIf { it.isNotBlank() }
            ?: extractFirstHeading(body)
            ?: id.replace("-", " ").replaceFirstChar { it.uppercase() }

        val title = rawTitle.trim()
        val slug = BengaliTextNormalizer.normalize(title)
            .replace(" ", "-")
            .replace(Regex("[^\\w\\-\\u0980-\\u09FF]"), "")

        val categoryId = frontMatter["category"]?.takeIf { it.isNotBlank() } ?: fallbackCategoryId
        val tags = extractTags(frontMatter["tags"])
        val summary = extractSummary(body)
        val sections = extractSections(canonicalId, body)
        val contentHash = Sha256Util.md5(rawContent.trim())
        val sha256Hash = Sha256Util.sha256(rawContent.trim())

        return ParsedMarkdownArticle(
            id = id,
            title = title,
            slug = slug,
            summary = summary,
            content = body,
            categoryId = categoryId,
            tags = tags,
            sections = sections,
            isTranslation = isTranslation,
            canonicalId = canonicalId,
            languageCode = languageCode,
            contentHash = contentHash,
            sha256Hash = sha256Hash
        )
    }

    private fun parseFrontMatter(content: String): Pair<Map<String, String>, String> {
        val frontMatter = mutableMapOf<String, String>()
        var body = content

        if (content.startsWith("---")) {
            val parts = content.split("---", limit = 3)
            if (parts.size >= 3) {
                val rawFm = parts[1]
                body = parts[2].trim()

                var currentKey: String? = null
                val tagList = mutableListOf<String>()

                for (line in rawFm.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#")) continue

                    if (trimmed.startsWith("- ") && currentKey == "tags") {
                        val tagVal = trimmed.removePrefix("- ").trim().trim('"', '\'')
                        if (tagVal.isNotBlank()) tagList.add(tagVal)
                    } else if (trimmed.contains(":")) {
                        val colonIndex = trimmed.indexOf(":")
                        val key = trimmed.substring(0, colonIndex).trim().lowercase()
                        val value = trimmed.substring(colonIndex + 1).trim().trim('"', '\'')
                        if (key == "tags" && value.isBlank()) {
                            currentKey = "tags"
                        } else {
                            currentKey = key
                            frontMatter[key] = value
                        }
                    }
                }

                if (tagList.isNotEmpty()) {
                    frontMatter["tags"] = tagList.joinToString(",")
                }
            }
        }
        return Pair(frontMatter, body)
    }

    private fun extractFirstHeading(body: String): String? {
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ")) {
                return trimmed.removePrefix("# ").trim()
            }
        }
        return null
    }

    private fun extractSummary(body: String): String {
        val paragraphs = body.split(Regex("\n\\s*\n"))
        for (p in paragraphs) {
            val trimmed = p.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("![")) {
                return trimmed.take(300)
            }
        }
        return ""
    }

    private fun extractTags(rawTags: String?): List<String> {
        if (rawTags.isNullOrBlank()) return emptyList()
        return rawTags.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun extractSections(articleId: String, body: String): List<SectionEntity> {
        val sections = mutableListOf<SectionEntity>()
        val lines = body.lines()
        var currentTitle = "ভূমিকা"
        var currentLevel = 2
        val currentContent = StringBuilder()
        var position = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("### ")) {
                if (currentContent.isNotBlank() || position > 0) {
                    position++
                    sections.add(
                        SectionEntity(
                            id = "$articleId-sec-$position",
                            articleId = articleId,
                            title = currentTitle,
                            content = currentContent.toString().trim(),
                            position = position,
                            level = currentLevel
                        )
                    )
                    currentContent.clear()
                }
                currentTitle = trimmed.removePrefix("### ").trim()
                currentLevel = 3
            } else if (trimmed.startsWith("## ")) {
                if (currentContent.isNotBlank() || position > 0) {
                    position++
                    sections.add(
                        SectionEntity(
                            id = "$articleId-sec-$position",
                            articleId = articleId,
                            title = currentTitle,
                            content = currentContent.toString().trim(),
                            position = position,
                            level = currentLevel
                        )
                    )
                    currentContent.clear()
                }
                currentTitle = trimmed.removePrefix("## ").trim()
                currentLevel = 2
            } else if (trimmed.startsWith("# ")) {
                // Main title skipped
                continue
            } else {
                currentContent.append(line).append("\n")
            }
        }

        if (currentContent.isNotBlank()) {
            position++
            sections.add(
                SectionEntity(
                    id = "$articleId-sec-$position",
                    articleId = articleId,
                    title = currentTitle,
                    content = currentContent.toString().trim(),
                    position = position,
                    level = currentLevel
                )
            )
        }

        return sections
    }
}
