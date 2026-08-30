package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppLanguage
import com.example.data.model.ReaderFontSize
import com.example.ui.components.ArticleCard
import com.example.ui.components.TableOfContentsDialog
import com.example.ui.viewmodel.ArticleViewModel
import com.example.util.RenderMarkdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderScreen(
    viewModel: ArticleViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToArticle: (String) -> Unit,
    onNavigateToCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val articleDetail by viewModel.articleDetail.collectAsStateWithLifecycle()
    val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()

    var showTocDialog by remember { mutableStateOf(false) }
    var currentFontSize by remember { mutableStateOf(ReaderFontSize.MEDIUM) }
    var showFontMenu by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }

    val isEnglishSelected = selectedLanguage == AppLanguage.ENGLISH
    val detail = articleDetail
    val hasEnglish = detail?.hasEnglishTranslation == true

    // Compute effective content depending on language and fallback
    val displayTitle = if (isEnglishSelected && hasEnglish && !detail?.englishTitle.isNullOrBlank()) {
        detail?.englishTitle!!
    } else {
        detail?.article?.title ?: "নিবন্ধ পাঠ"
    }

    val displaySummary = if (isEnglishSelected && hasEnglish && !detail?.englishSummary.isNullOrBlank()) {
        detail?.englishSummary!!
    } else {
        detail?.article?.summary ?: ""
    }

    val displayContent = if (isEnglishSelected && hasEnglish && !detail?.englishContent.isNullOrBlank()) {
        detail?.englishContent!!
    } else {
        detail?.article?.content ?: ""
    }

    val displaySections = if (isEnglishSelected && hasEnglish && detail?.englishSections?.isNotEmpty() == true) {
        detail.englishSections
    } else {
        detail?.sections ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayTitle,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("reader_back_button")
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Language Switcher Menu / Action
                    Box {
                        FilledTonalButton(
                            onClick = { showLangMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .padding(end = 4.dp)
                                .testTag("reader_language_button"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "ভাষা নির্বাচন",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectedLanguage.label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("বাংলা (মৌলিক)", fontWeight = if (selectedLanguage == AppLanguage.BENGALI) FontWeight.Bold else FontWeight.Normal)
                                    }
                                },
                                onClick = {
                                    viewModel.setLanguage(AppLanguage.BENGALI)
                                    showLangMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("English (Translation)", fontWeight = if (selectedLanguage == AppLanguage.ENGLISH) FontWeight.Bold else FontWeight.Normal)
                                        if (hasEnglish) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("(Available)", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.setLanguage(AppLanguage.ENGLISH)
                                    showLangMenu = false
                                }
                            )
                        }
                    }

                    // Table of Contents Button
                    IconButton(
                        onClick = { showTocDialog = true },
                        modifier = Modifier.testTag("reader_toc_button")
                    ) {
                        Icon(imageVector = Icons.Default.List, contentDescription = "সূচিপত্র")
                    }

                    // Font Size Adjuster
                    Box {
                        IconButton(onClick = { showFontMenu = true }) {
                            Icon(imageVector = Icons.Default.FormatSize, contentDescription = "অক্ষরের আকার")
                        }
                        DropdownMenu(
                            expanded = showFontMenu,
                            onDismissRequest = { showFontMenu = false }
                        ) {
                            ReaderFontSize.values().forEach { size ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (isEnglishSelected) size.titleEn else size.titleBn,
                                            fontWeight = if (currentFontSize == size) FontWeight.Bold else FontWeight.Normal,
                                            color = if (currentFontSize == size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        currentFontSize = size
                                        showFontMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Bookmark Toggle
                    IconButton(
                        onClick = { viewModel.toggleBookmark() },
                        modifier = Modifier.testTag("reader_bookmark_button")
                    ) {
                        val isBookmarked = detail?.isBookmarked == true
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "বুকমার্ক",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Share Button
                    IconButton(
                        onClick = {
                            val art = detail?.article ?: return@IconButton
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, displayTitle)
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "NexVora Encyclopedia:\n\n$displayTitle\n\n$displaySummary\n\n(অফলাইন বিশ্বকোষ)"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "শেয়ার করুন"))
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "শেয়ার")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (displaySections.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showTocDialog = true },
                    icon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) },
                    text = { Text(if (isEnglishSelected) "Contents (${displaySections.size})" else "সূচিপত্র (${displaySections.size})") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (detail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val article = detail.article
            val category = detail.category

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Category Breadcrumb
                if (category != null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onNavigateToCategory(category.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = category.path,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Translation Fallback Notice Banner if English is chosen but not available
                if (isEnglishSelected && !hasEnglish) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "English translation not yet available",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "English version is not available for this article yet. Displaying original Bengali content.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Article Main Title
                item {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = (26 * currentFontSize.scaleFactor).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Summary Card
                if (displaySummary.isNotBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isEnglishSelected && hasEnglish) "Summary" else "সংক্ষিপ্ত পরিচিতি",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = displaySummary,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = (15 * currentFontSize.scaleFactor).sp,
                                        lineHeight = (22 * currentFontSize.scaleFactor).sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Render Main Markdown Body
                item {
                    RenderMarkdown(
                        markdown = displayContent,
                        fontSize = currentFontSize,
                        onInternalArticleClick = { targetArticleId ->
                            onNavigateToArticle(targetArticleId)
                        },
                        onExternalLinkClick = {}
                    )
                }

                // Tags Section
                if (detail.tags.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(
                                text = if (isEnglishSelected && hasEnglish) "Tags & Topics" else "ট্যাগ ও বিষয়সমূহ",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                detail.tags.forEach { tag ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tag.name) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Tag,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Related Articles
                if (detail.relatedArticles.isNotEmpty()) {
                    item {
                        Text(
                            text = if (isEnglishSelected && hasEnglish) "Related Articles" else "সম্পর্কিত অন্যান্য নিবন্ধ",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }

                    items(detail.relatedArticles.size) { index ->
                        val related = detail.relatedArticles[index]
                        ArticleCard(
                            article = related,
                            onClick = { onNavigateToArticle(related.id) }
                        )
                    }
                }

                // Bottom padding for FAB
                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }

            // Table of Contents Dialog
            if (showTocDialog) {
                TableOfContentsDialog(
                    sections = displaySections,
                    onSectionClick = { pos ->
                        coroutineScope.launch {
                            listState.animateScrollToItem(index = 3 + (pos * 2).coerceAtMost(10))
                        }
                    },
                    onDismiss = { showTocDialog = false }
                )
            }
        }
    }
}
