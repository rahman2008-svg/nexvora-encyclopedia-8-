#!/usr/bin/env python3
"""
NexVora Encyclopedia - Build-Time Content Compiler & Database Generator
Recursively scans 'content/', parses Markdown + YAML frontmatter, extracts metadata,
validates integrity, generates normalized SQLite database with FTS, and creates content stats.
"""

import os
import sys
import re
import json
import sqlite3
import hashlib
import time
from datetime import datetime
from pathlib import Path

# Paths relative to project root
PROJECT_ROOT = Path(__file__).resolve().parent.parent
CONTENT_DIR = PROJECT_ROOT / "content"
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
ASSETS_IMAGES_DIR = PROJECT_ROOT / "assets" / "images"
DB_OUTPUT_PATH = ASSETS_DIR / "encyclopedia.db"
STATS_OUTPUT_PATH = ASSETS_DIR / "content-stats.json"

class ContentCompiler:
    def __init__(self, content_dir, output_db_path, output_stats_path):
        self.content_dir = Path(content_dir)
        self.output_db_path = Path(output_db_path)
        self.output_stats_path = Path(output_stats_path)
        self.categories = {} # id -> dict
        self.articles = {}   # id -> dict
        self.article_translations = [] # list of dict
        self.sections = []   # list of dict
        self.section_translations = [] # list of dict
        self.tags = {}       # name -> id
        self.article_tags = [] # (article_id, tag_id)
        self.relations = []  # dicts
        self.images = []     # dicts
        self.errors = []
        self.warnings = []
        self.broken_links_count = 0
        self.missing_images_count = 0
        self.total_content_chars = 0
        self.largest_article = None

    def log(self, msg):
        print(f"[NexVora Compiler] {msg}")

    def error(self, file_path, msg, line=None, suggested_fix=None):
        self.errors.append({
            "file": str(file_path),
            "error": msg,
            "line": line,
            "suggested_fix": suggested_fix
        })

    def warning(self, file_path, msg):
        self.warnings.append({
            "file": str(file_path),
            "warning": msg
        })

    def slugify(self, text):
        # Normalize to URL/path-friendly slug while supporting Unicode/Bengali
        text = text.strip().lower()
        text = re.sub(r'[\s_]+', '-', text)
        text = re.sub(r'[^\w\-\u0980-\u09FF]', '', text)
        return text.strip('-')

    def parse_front_matter(self, content):
        front_matter = {}
        body = content
        if content.startswith("---"):
            parts = content.split("---", 2)
            if len(parts) >= 3:
                raw_fm = parts[1]
                body = parts[2].strip()
                # Simple robust YAML parser for key-values and lists
                current_key = None
                for line in raw_fm.splitlines():
                    line_str = line.strip()
                    if not line_str or line_str.startswith("#"):
                        continue
                    if line_str.startswith("- ") and current_key:
                        val = line_str[2:].strip().strip('"').strip("'")
                        if isinstance(front_matter[current_key], list):
                            front_matter[current_key].append(val)
                    elif ":" in line:
                        k, v = line.split(":", 1)
                        k = k.strip()
                        v = v.strip().strip('"').strip("'")
                        if not v:
                            front_matter[k] = []
                            current_key = k
                        else:
                            front_matter[k] = v
                            current_key = None
        return front_matter, body

    def extract_sections(self, article_id, body):
        # Extract H2 / H3 headings and their respective text
        lines = body.splitlines()
        sections = []
        current_section = None
        pos = 0

        for line in lines:
            h2_match = re.match(r'^##\s+(.+)$', line)
            h3_match = re.match(r'^###\s+(.+)$', line)
            if h2_match:
                if current_section:
                    sections.append(current_section)
                pos += 1
                current_section = {
                    "id": f"{article_id}_sec_{pos}",
                    "articleId": article_id,
                    "title": h2_match.group(1).strip(),
                    "content": "",
                    "position": pos,
                    "level": 2
                }
            elif h3_match:
                if current_section:
                    sections.append(current_section)
                pos += 1
                current_section = {
                    "id": f"{article_id}_sec_{pos}",
                    "articleId": article_id,
                    "title": h3_match.group(1).strip(),
                    "content": "",
                    "position": pos,
                    "level": 3
                }
            else:
                if current_section:
                    current_section["content"] += line + "\n"
        
        if current_section:
            sections.append(current_section)
        
        # Clean section contents
        for s in sections:
            s["content"] = s["content"].strip()

        return sections

    def extract_summary(self, body, explicit_summary=None):
        if explicit_summary and explicit_summary.strip():
            return explicit_summary.strip()
        # Find first non-empty paragraph that doesn't start with '#'
        lines = body.splitlines()
        para_lines = []
        for line in lines:
            clean = line.strip()
            if not clean:
                if para_lines:
                    break
                continue
            if clean.startswith("#"):
                continue
            para_lines.append(clean)
        
        if para_lines:
            summary = " ".join(para_lines)
            # Remove Markdown links and formatting from summary
            summary = re.sub(r'\[([^\]]+)\]\([^\)]+\)', r'\1', summary)
            summary = re.sub(r'[*_`#]', '', summary)
            return summary[:280] + ("..." if len(summary) > 280 else "")
        return ""

    def extract_title(self, file_path, body, explicit_title=None):
        if explicit_title and explicit_title.strip():
            return explicit_title.strip()
        # Find first H1
        for line in body.splitlines():
            h1_match = re.match(r'^#\s+(.+)$', line.strip())
            if h1_match:
                return h1_match.group(1).strip()
        return file_path.stem

    def discover_categories(self):
        # We will discover and record categories from article directory paths
        # during process_articles to ensure every category has valid hierarchy
        pass

    def process_articles(self):
        seen_titles = {} # title.lower() -> file_path

        for root, dirs, files in os.walk(self.content_dir):
            rel_dir = os.path.relpath(root, self.content_dir)
            
            # Skip hidden directories like .git
            if any(part.startswith(".") for part in Path(rel_dir).parts):
                continue

            for file in sorted(files):
                if not file.endswith(".md"):
                    continue

                # Explicitly ignore authoring templates, guides, and documentation files
                file_upper = file.upper()
                if (file.startswith(".") or 
                    file.startswith("_") or 
                    file_upper in ["ARTICLE_TEMPLATE.MD", "README.MD", "TEMPLATE.MD", "CONTRIBUTING.MD", "GUIDE.MD"] or
                    file.endswith(".template.md")):
                    continue

                file_path = Path(root) / file
                rel_file_path = file_path.relative_to(self.content_dir)
                
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        raw_content = f.read()
                except UnicodeDecodeError as e:
                    self.error(rel_file_path, f"Invalid Unicode/UTF-8 encoding in file: {e}", 1, "Save file as UTF-8 encoding")
                    continue
                except Exception as e:
                    self.error(rel_file_path, f"Failed to read file: {e}", 1, "Check file permissions and format")
                    continue

                if not raw_content.strip():
                    self.error(rel_file_path, "Empty article file", 1, "Add article content, front matter, and headings")
                    continue

                fm, body = self.parse_front_matter(raw_content)
                
                # Check for bilingual translation file
                is_translation = False
                lang_code = "bn"
                canonical_target_id = None

                if file.endswith(".en.md"):
                    is_translation = True
                    lang_code = "en"
                    canonical_target_id = self.slugify(file[:-6])
                elif file.endswith("_en.md"):
                    is_translation = True
                    lang_code = "en"
                    canonical_target_id = self.slugify(file[:-6])
                elif str(fm.get("language", "")).lower() in ["en", "english"] or str(fm.get("lang", "")).lower() in ["en", "english"]:
                    is_translation = True
                    lang_code = "en"
                    canonical_target_id = self.slugify(str(fm.get("canonical_id") or fm.get("article_id") or fm.get("id") or file_path.stem))

                if is_translation:
                    title = self.extract_title(file_path, body, fm.get("title"))
                    if not title:
                        self.error(rel_file_path, "Missing title in translation file", 1, "Add 'title: ...' or '# Title'")
                        continue
                    trans_id = f"{canonical_target_id}_{lang_code}"
                    summary = self.extract_summary(body, fm.get("summary"))
                    self.article_translations.append({
                        "id": trans_id,
                        "articleId": canonical_target_id,
                        "languageCode": lang_code,
                        "title": title,
                        "summary": summary,
                        "content": body,
                        "file_path": str(rel_file_path)
                    })
                    secs = self.extract_sections(canonical_target_id, body)
                    for s in secs:
                        self.section_translations.append({
                            "id": f"{trans_id}_sec_{s['position']}",
                            "articleId": canonical_target_id,
                            "languageCode": lang_code,
                            "title": s["title"],
                            "content": s["content"],
                            "position": s["position"],
                            "level": s["level"]
                        })
                    continue

                # Derive and validate Article ID
                raw_id = fm.get("id") or file_path.stem
                if not raw_id:
                    self.error(rel_file_path, "Missing article ID", 1, "Specify 'id: your-slug' in YAML front-matter")
                    continue

                article_id = self.slugify(str(raw_id))
                if not article_id:
                    self.error(rel_file_path, f"Invalid article ID '{raw_id}'", 1, "Use alphanumeric characters and hyphens for article ID")
                    continue

                if article_id in self.articles:
                    existing_file = self.articles[article_id]["file_path"]
                    self.error(
                        rel_file_path,
                        f"DUPLICATE ARTICLE ID: '{article_id}'! This ID conflicts with file: '{existing_file}'",
                        1,
                        "Change the 'id' in front-matter to a unique slug."
                    )
                    continue

                # Title
                title = self.extract_title(file_path, body, fm.get("title"))
                if not title:
                    self.error(rel_file_path, "Missing title or H1 heading", 1, "Add 'title: ...' in front matter or a '# Title' heading")
                    continue

                title_clean = title.strip()
                title_key = title_clean.lower()
                if title_key in seen_titles:
                    self.warning(
                        rel_file_path,
                        f"Duplicate title '{title_clean}' also found in '{seen_titles[title_key]}'"
                    )
                else:
                    seen_titles[title_key] = rel_file_path

                # Automatic Category & Subcategory Detection from Directory Structure
                if rel_dir == ".":
                    category_id = "general"
                    if category_id not in self.categories:
                        self.categories[category_id] = {
                            "id": category_id,
                            "name": "General",
                            "parentId": None,
                            "path": "General",
                            "depth": 1
                        }
                else:
                    cat_parts = Path(rel_dir).parts
                    path_accum = []
                    parent_id = None
                    for depth, part in enumerate(cat_parts, 1):
                        path_accum.append(part)
                        curr_cat_id = self.slugify("-".join(path_accum))
                        curr_cat_path = " → ".join(path_accum)
                        if curr_cat_id not in self.categories:
                            self.categories[curr_cat_id] = {
                                "id": curr_cat_id,
                                "name": part.replace("-", " "),
                                "parentId": parent_id,
                                "path": curr_cat_path,
                                "depth": depth
                            }
                        parent_id = curr_cat_id
                    category_id = self.slugify("-".join(cat_parts))

                slug = self.slugify(title)
                summary = self.extract_summary(body, fm.get("summary"))
                
                # Hash content
                content_hash = hashlib.sha256(raw_content.encode("utf-8")).hexdigest()

                now_ts = int(time.time() * 1000)
                article_data = {
                    "id": article_id,
                    "title": title,
                    "slug": slug,
                    "summary": summary,
                    "content": body,
                    "categoryId": category_id,
                    "createdAt": now_ts,
                    "updatedAt": now_ts,
                    "contentHash": content_hash,
                    "file_path": str(rel_file_path),
                    "fm": {k: str(v) if isinstance(v, Path) else v for k, v in fm.items()}
                }
                self.articles[article_id] = article_data

                # Sections
                secs = self.extract_sections(article_id, body)
                self.sections.extend(secs)

                # Tags
                tags_list = fm.get("tags") or []
                if isinstance(tags_list, str):
                    tags_list = [t.strip() for t in tags_list.split(",")]
                for tag_name in tags_list:
                    tag_name = tag_name.strip()
                    if not tag_name:
                        continue
                    tag_id = self.slugify(tag_name)
                    if tag_id not in self.tags:
                        self.tags[tag_id] = tag_name
                    self.article_tags.append((article_id, tag_id))

                # Relations
                related_list = fm.get("related") or []
                if isinstance(related_list, str):
                    related_list = [r.strip() for r in related_list.split(",")]
                for rel_id in related_list:
                    clean_rel_id = self.slugify(rel_id)
                    rel_record_id = f"{article_id}_{clean_rel_id}"
                    self.relations.append({
                        "id": rel_record_id,
                        "articleId": article_id,
                        "relatedArticleId": clean_rel_id,
                        "relationType": "see_also"
                    })

                # Extract inline internal links `[Text](article:target_id)`
                internal_links = re.findall(r'\[([^\]]+)\]\(article:([^\)]+)\)', body)
                for link_text, target_id in internal_links:
                    clean_target = self.slugify(target_id)
                    rel_record_id = f"{article_id}_{clean_target}"
                    if not any(r["id"] == rel_record_id for r in self.relations):
                        self.relations.append({
                            "id": rel_record_id,
                            "articleId": article_id,
                            "relatedArticleId": clean_target,
                            "relationType": "inline_reference"
                        })

                # Extract images `![Caption](path)`
                img_matches = re.findall(r'!\[([^\]]*)\]\(([^\)]+)\)', body)
                for idx, (caption, img_path) in enumerate(img_matches, 1):
                    img_id = f"{article_id}_img_{idx}"
                    self.images.append({
                        "id": img_id,
                        "articleId": article_id,
                        "path": img_path,
                        "caption": caption,
                        "position": idx
                    })

                content_len = len(body)
                self.total_content_chars += content_len
                if self.largest_article is None or content_len > self.largest_article.get("chars", 0):
                    self.largest_article = {
                        "id": article_id,
                        "title": title,
                        "chars": content_len
                    }

    def validate(self):
        # Validate internal relations and inline links
        self.broken_links_count = 0
        for rel in self.relations:
            art_id = rel["articleId"]
            target_id = rel["relatedArticleId"]
            if target_id not in self.articles:
                self.broken_links_count += 1
                self.warning(self.articles[art_id]["file_path"], f"Related article ID '{target_id}' not found in encyclopedia.")

        # Validate image asset paths
        self.missing_images_count = 0
        for img in self.images:
            img_path = img["path"].strip()
            art_id = img["articleId"]
            if img_path.startswith("assets/images/"):
                resolved_img = PROJECT_ROOT / img_path
                if not resolved_img.exists():
                    self.missing_images_count += 1
                    self.warning(self.articles[art_id]["file_path"], f"Referenced image file '{img_path}' not found on disk.")
                elif resolved_img.stat().st_size > 2 * 1024 * 1024:
                    self.warning(self.articles[art_id]["file_path"], f"Image asset '{img_path}' is larger than 2MB ({resolved_img.stat().st_size / (1024*1024):.2f} MB). Consider compressing.")

        # Validate translations
        for tr in self.article_translations:
            art_id = tr["articleId"]
            if art_id not in self.articles:
                self.warning(tr["file_path"], f"Translation '{tr['id']}' references base article '{art_id}' which does not exist.")

        # Print validation summary
        total_articles = len(self.articles)
        total_translations = len(self.article_translations)
        subcategories_count = sum(1 for c in self.categories.values() if c.get("depth", 1) > 1)
        self.log(f"=== CONTENT VALIDATION REPORT ===")
        self.log(f"Total articles found: {total_articles} (Bengali base)")
        self.log(f"Total translations found: {total_translations} (English/Translations)")
        self.log(f"Categories discovered: {len(self.categories)} (Root + Subcategories: {subcategories_count})")
        self.log(f"Sections extracted: {len(self.sections)} (Translations: {len(self.section_translations)})")
        self.log(f"Tags mapped: {len(self.tags)}")
        self.log(f"Relations & internal links mapped: {len(self.relations)}")
        self.log(f"Images referenced: {len(self.images)}")
        self.log(f"Broken internal links: {self.broken_links_count}")
        self.log(f"Errors: {len(self.errors)}")
        self.log(f"Warnings: {len(self.warnings)}")

        if self.errors:
            print("\nCRITICAL ERRORS:")
            for err in self.errors:
                print(f"FILE: {err['file']}\nERROR: {err['error']}\nLINE: {err.get('line')}\nSUGGESTED FIX: {err.get('suggested_fix')}\n")
            raise ValueError(f"Content compilation failed with {len(self.errors)} errors.")

    def get_room_identity_hash(self):
        # First check generated AppDatabase_Impl.kt if present
        impl_path = PROJECT_ROOT / "app" / "build" / "generated" / "ksp" / "debug" / "kotlin" / "com" / "example" / "data" / "local" / "AppDatabase_Impl.kt"
        if impl_path.exists():
            try:
                content = impl_path.read_text(encoding="utf-8")
                match = re.search(r"VALUES\(42,\s*'([a-f0-9]+)'\)", content)
                if match:
                    return match.group(1)
            except Exception as e:
                self.log(f"Warning: Could not read AppDatabase_Impl.kt: {e}")

        # Look for schema JSON generated by Room compiler
        schema_path = PROJECT_ROOT / "app" / "schemas" / "com.example.data.local.AppDatabase" / "1.json"
        if schema_path.exists():
            try:
                with open(schema_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    h = data.get("identityHash")
                    if h:
                        return h
            except Exception as e:
                self.log(f"Warning: Could not read schema JSON: {e}")
        # Default compiled identity hash for AppDatabase version 1
        return "d9e8f40541750554ae6ed422ef4697b9"

    def generate_database(self):
        self.output_db_path.parent.mkdir(parents=True, exist_ok=True)
        if self.output_db_path.exists():
            self.output_db_path.unlink()

        conn = sqlite3.connect(str(self.output_db_path))
        cursor = conn.cursor()

        # High-performance PRAGMAs for bulk insertion and 100,000+ article scalability
        cursor.execute("PRAGMA synchronous = OFF")
        cursor.execute("PRAGMA journal_mode = MEMORY")
        cursor.execute("PRAGMA temp_store = MEMORY")
        cursor.execute("PRAGMA page_size = 4096")
        cursor.execute("PRAGMA cache_size = 50000")

        # Create Schema matching Room Entities and RoomOpenDelegate exactly
        cursor.executescript("""
        CREATE TABLE IF NOT EXISTS `categories` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `parentId` TEXT,
            `path` TEXT NOT NULL,
            `depth` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`);

        CREATE TABLE IF NOT EXISTS `articles` (
            `id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `slug` TEXT NOT NULL,
            `summary` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `categoryId` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `contentHash` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_articles_categoryId` ON `articles` (`categoryId`);
        CREATE INDEX IF NOT EXISTS `index_articles_title` ON `articles` (`title`);

        CREATE TABLE IF NOT EXISTS `sections` (
            `id` TEXT NOT NULL,
            `articleId` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `position` INTEGER NOT NULL,
            `level` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_sections_articleId` ON `sections` (`articleId`);

        CREATE TABLE IF NOT EXISTS `article_translations` (
            `articleId` TEXT NOT NULL,
            `languageCode` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `summary` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`articleId`, `languageCode`)
        );
        CREATE INDEX IF NOT EXISTS `index_article_translations_articleId` ON `article_translations` (`articleId`);
        CREATE INDEX IF NOT EXISTS `index_article_translations_languageCode` ON `article_translations` (`languageCode`);

        CREATE TABLE IF NOT EXISTS `section_translations` (
            `id` TEXT NOT NULL,
            `articleId` TEXT NOT NULL,
            `languageCode` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `position` INTEGER NOT NULL,
            `level` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_section_translations_articleId` ON `section_translations` (`articleId`);
        CREATE INDEX IF NOT EXISTS `index_section_translations_articleId_languageCode` ON `section_translations` (`articleId`, `languageCode`);

        CREATE TABLE IF NOT EXISTS `tags` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        );

        CREATE TABLE IF NOT EXISTS `article_tags` (
            `articleId` TEXT NOT NULL,
            `tagId` TEXT NOT NULL,
            PRIMARY KEY(`articleId`, `tagId`)
        );
        CREATE INDEX IF NOT EXISTS `index_article_tags_tagId` ON `article_tags` (`tagId`);

        CREATE TABLE IF NOT EXISTS `article_relations` (
            `id` TEXT NOT NULL,
            `articleId` TEXT NOT NULL,
            `relatedArticleId` TEXT NOT NULL,
            `relationType` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_article_relations_articleId` ON `article_relations` (`articleId`);
        CREATE INDEX IF NOT EXISTS `index_article_relations_relatedArticleId` ON `article_relations` (`relatedArticleId`);

        CREATE TABLE IF NOT EXISTS `bookmarks` (
            `articleId` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`articleId`)
        );

        CREATE TABLE IF NOT EXISTS `reading_history` (
            `articleId` TEXT NOT NULL,
            `lastReadAt` INTEGER NOT NULL,
            PRIMARY KEY(`articleId`)
        );

        CREATE TABLE IF NOT EXISTS `article_images` (
            `id` TEXT NOT NULL,
            `articleId` TEXT NOT NULL,
            `path` TEXT NOT NULL,
            `caption` TEXT NOT NULL,
            `position` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        );
        CREATE INDEX IF NOT EXISTS `index_article_images_articleId` ON `article_images` (`articleId`);

        -- FTS4 Full-Text Search Virtual Table matching Room FtsTableInfo
        CREATE VIRTUAL TABLE IF NOT EXISTS `articles_fts` USING FTS4(
            `id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `summary` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            content=`articles`
        );

        -- Room FTS Content Sync Triggers
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_BEFORE_UPDATE BEFORE UPDATE ON `articles` BEGIN DELETE FROM `articles_fts` WHERE `docid`=OLD.`rowid`; END;
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_BEFORE_DELETE BEFORE DELETE ON `articles` BEGIN DELETE FROM `articles_fts` WHERE `docid`=OLD.`rowid`; END;
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_AFTER_UPDATE AFTER UPDATE ON `articles` BEGIN INSERT INTO `articles_fts`(`docid`, `id`, `title`, `summary`, `content`) VALUES (NEW.`rowid`, NEW.`id`, NEW.`title`, NEW.`summary`, NEW.`content`); END;
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_AFTER_INSERT AFTER INSERT ON `articles` BEGIN INSERT INTO `articles_fts`(`docid`, `id`, `title`, `summary`, `content`) VALUES (NEW.`rowid`, NEW.`id`, NEW.`title`, NEW.`summary`, NEW.`content`); END;

        -- Room Master Table
        CREATE TABLE IF NOT EXISTS room_master_table (
            id INTEGER PRIMARY KEY,
            identity_hash TEXT
        );
        """)

        # Insert Room Identity Hash
        identity_hash = self.get_room_identity_hash()
        cursor.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (identity_hash,))

        # Helper for chunked executemany
        def chunked_executemany(sql, rows, chunk_size=5000):
            for i in range(0, len(rows), chunk_size):
                cursor.executemany(sql, rows[i:i + chunk_size])

        # Insert Categories
        cat_rows = [
            (cat["id"], cat["name"], cat["parentId"], cat["path"], cat["depth"])
            for cat in self.categories.values()
        ]
        chunked_executemany("INSERT INTO categories (id, name, parentId, path, depth) VALUES (?, ?, ?, ?, ?)", cat_rows)

        # Insert Articles
        art_rows = [
            (art["id"], art["title"], art["slug"], art["summary"], art["content"], art["categoryId"], art["createdAt"], art["updatedAt"], art["contentHash"])
            for art in self.articles.values()
        ]
        chunked_executemany("INSERT INTO articles (id, title, slug, summary, content, categoryId, createdAt, updatedAt, contentHash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", art_rows)

        # Insert Sections
        sec_rows = [
            (sec["id"], sec["articleId"], sec["title"], sec["content"], sec["position"], sec["level"])
            for sec in self.sections
        ]
        chunked_executemany("INSERT INTO sections (id, articleId, title, content, position, level) VALUES (?, ?, ?, ?, ?, ?)", sec_rows)

        # Insert Article Translations
        trans_rows = [
            (tr["articleId"], tr["languageCode"], tr["title"], tr["summary"], tr["content"], int(time.time() * 1000))
            for tr in self.article_translations
        ]
        chunked_executemany("INSERT OR REPLACE INTO article_translations (articleId, languageCode, title, summary, content, updatedAt) VALUES (?, ?, ?, ?, ?, ?)", trans_rows)

        # Insert Section Translations
        sec_trans_rows = [
            (st["id"], st["articleId"], st["languageCode"], st["title"], st["content"], st["position"], st["level"])
            for st in self.section_translations
        ]
        chunked_executemany("INSERT OR REPLACE INTO section_translations (id, articleId, languageCode, title, content, position, level) VALUES (?, ?, ?, ?, ?, ?, ?)", sec_trans_rows)

        # Insert Tags
        tag_rows = [
            (tag_id, tag_name)
            for tag_id, tag_name in self.tags.items()
        ]
        chunked_executemany("INSERT INTO tags (id, name) VALUES (?, ?)", tag_rows)

        # Insert Article-Tags
        chunked_executemany("INSERT OR IGNORE INTO article_tags (articleId, tagId) VALUES (?, ?)", self.article_tags)

        # Insert Relations
        rel_rows = [
            (rel["id"], rel["articleId"], rel["relatedArticleId"], rel["relationType"])
            for rel in self.relations
        ]
        chunked_executemany("INSERT OR IGNORE INTO article_relations (id, articleId, relatedArticleId, relationType) VALUES (?, ?, ?, ?)", rel_rows)

        # Insert Images
        img_rows = [
            (img["id"], img["articleId"], img["path"], img["caption"], img["position"])
            for img in self.images
        ]
        chunked_executemany("INSERT INTO article_images (id, articleId, path, caption, position) VALUES (?, ?, ?, ?, ?)", img_rows)

        # Populate FTS4 Index
        cursor.execute("INSERT INTO articles_fts(articles_fts) VALUES('rebuild')")

        # Verify DB integrity and FTS indexing
        cursor.execute("PRAGMA integrity_check")
        integrity_res = cursor.fetchone()
        if integrity_res and integrity_res[0] != "ok":
            raise ValueError(f"SQLite integrity check failed: {integrity_res}")

        cursor.execute("SELECT COUNT(*) FROM articles")
        art_count = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM articles_fts")
        fts_count = cursor.fetchone()[0]
        self.log(f"Database validation: {art_count} articles, {fts_count} FTS indexed entries.")

        # Optimize database before final commit
        cursor.execute("PRAGMA optimize")

        conn.commit()
        conn.close()

        db_size_bytes = self.output_db_path.stat().st_size
        self.log(f"Successfully compiled SQLite database: {self.output_db_path} ({db_size_bytes / 1024:.2f} KB)")

        # Generate rich dynamic content-stats.json
        subcategories_count = sum(1 for c in self.categories.values() if c.get("depth", 1) > 1)
        avg_chars = int(self.total_content_chars / len(self.articles)) if len(self.articles) > 0 else 0
        stats_data = {
            "version": "1.0.0",
            "compiledAt": int(time.time() * 1000),
            "articles": len(self.articles),
            "totalArticles": len(self.articles),
            "bengaliArticles": len(self.articles),
            "englishArticles": len(self.article_translations),
            "articlesWithBothLanguages": len(self.article_translations),
            "articlesWithoutEnglishTranslation": max(0, len(self.articles) - len(self.article_translations)),
            "categories": len(self.categories),
            "totalCategories": len(self.categories),
            "subcategories": subcategories_count,
            "totalSubcategories": subcategories_count,
            "sections": len(self.sections),
            "totalSections": len(self.sections),
            "sectionTranslations": len(self.section_translations),
            "tags": len(self.tags),
            "totalTags": len(self.tags),
            "relations": len(self.relations),
            "totalRelations": len(self.relations),
            "images": len(self.images),
            "totalImages": len(self.images),
            "ftsEntries": fts_count,
            "databaseSizeBytes": db_size_bytes,
            "databaseSizeFormatted": f"{db_size_bytes / (1024 * 1024):.2f} MB" if db_size_bytes >= 1024 * 1024 else f"{db_size_bytes / 1024:.2f} KB",
            "largestArticle": self.largest_article or {},
            "averageArticleSizeChars": avg_chars,
            "totalContentSizeChars": self.total_content_chars,
            "brokenInternalLinks": self.broken_links_count,
            "missingImageReferences": self.missing_images_count,
            "ftsEnabled": True
        }

        with open(self.output_stats_path, "w", encoding="utf-8") as f:
            json.dump(stats_data, f, indent=2, ensure_ascii=False)
        self.log(f"Generated stats report: {self.output_stats_path}")

    def generate_manifest_and_payloads(self):
        self.log("Generating remote content manifest and incremental update payloads...")
        updates_dir = PROJECT_ROOT / "updates"
        articles_updates_dir = updates_dir / "articles"
        articles_updates_dir.mkdir(parents=True, exist_ok=True)

        manifest_articles = {}
        sections_by_article = {}
        for s in self.sections:
            sections_by_article.setdefault(s["articleId"], []).append(s)

        tags_by_article = {}
        for art_id, tag_id in self.article_tags:
            tag_name = self.tags.get(tag_id, tag_id)
            tags_by_article.setdefault(art_id, []).append(tag_name)

        relations_by_article = {}
        for r in self.relations:
            relations_by_article.setdefault(r["articleId"], []).append(r)

        translations_by_article = {}
        for t in self.article_translations:
            translations_by_article[t["articleId"]] = t

        sec_trans_by_article = {}
        for st in self.section_translations:
            sec_trans_by_article.setdefault(st["articleId"], []).append(st)

        for art_id, art in self.articles.items():
            cat = self.categories.get(art["categoryId"])
            art_secs = sections_by_article.get(art_id, [])
            art_tags = tags_by_article.get(art_id, [])
            art_rels = relations_by_article.get(art_id, [])

            en_trans = translations_by_article.get(art_id)
            en_payload = None
            has_en = False
            en_hash = None
            if en_trans:
                has_en = True
                en_secs = sec_trans_by_article.get(art_id, [])
                en_hash = hashlib.sha256(en_trans["content"].encode("utf-8")).hexdigest()
                en_payload = {
                    "title": en_trans["title"],
                    "summary": en_trans["summary"],
                    "content": en_trans["content"],
                    "updatedAt": en_trans.get("updatedAt", art["updatedAt"]),
                    "sha256": en_hash,
                    "sections": [
                        {
                            "id": s["id"],
                            "articleId": art_id,
                            "languageCode": "en",
                            "title": s["title"],
                            "content": s["content"],
                            "position": s["position"],
                            "level": s["level"]
                        }
                        for s in en_secs
                    ]
                }

            payload = {
                "id": art["id"],
                "title": art["title"],
                "slug": art["slug"],
                "summary": art["summary"],
                "content": art["content"],
                "categoryId": art["categoryId"],
                "createdAt": art["createdAt"],
                "updatedAt": art["updatedAt"],
                "contentHash": art["contentHash"],
                "sha256": hashlib.sha256(art["content"].encode("utf-8")).hexdigest(),
                "category": cat,
                "sections": [
                    {
                        "id": s["id"],
                        "articleId": s["articleId"],
                        "title": s["title"],
                        "content": s["content"],
                        "position": s["position"],
                        "level": s["level"]
                    }
                    for s in art_secs
                ],
                "tags": art_tags,
                "relations": [
                    {
                        "id": r["id"],
                        "articleId": r["articleId"],
                        "relatedArticleId": r["relatedArticleId"],
                        "relationType": r["relationType"]
                    }
                    for r in art_rels
                ],
                "englishTranslation": en_payload
            }

            art_payload_file = articles_updates_dir / f"{art_id}.json"
            with open(art_payload_file, "w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)

            manifest_articles[art_id] = {
                "id": art["id"],
                "title": art["title"],
                "slug": art["slug"],
                "categoryId": art["categoryId"],
                "contentHash": art["contentHash"],
                "updatedAt": art["updatedAt"],
                "hasEnglish": has_en,
                "englishHash": en_hash,
                "updateUrl": f"updates/articles/{art_id}.json",
                "rawPath": art.get("file_path", "")
            }

        now_utc_str = datetime.utcnow().strftime("%Y%m%d-%H%M%S")
        manifest_data = {
            "schemaVersion": 1,
            "contentVersion": 1,
            "revision": now_utc_str,
            "generatedAt": int(time.time() * 1000),
            "totalArticles": len(self.articles),
            "defaultRepo": "https://raw.githubusercontent.com/Prince-AR-Abdur-Rahman/nexvora-encyclopedia/main/",
            "categories": list(self.categories.values()),
            "articles": manifest_articles,
            "deletedArticleIds": []
        }

        manifest_paths = [
            PROJECT_ROOT / "content-manifest.json",
            ASSETS_DIR / "content-manifest.json",
            updates_dir / "content-manifest.json"
        ]

        for mp in manifest_paths:
            mp.parent.mkdir(parents=True, exist_ok=True)
            with open(mp, "w", encoding="utf-8") as f:
                json.dump(manifest_data, f, indent=2, ensure_ascii=False)
            self.log(f"Generated manifest: {mp}")

    def compile(self):
        self.log("Starting NexVora content compilation pipeline...")
        self.discover_categories()
        self.process_articles()
        self.validate()
        self.generate_database()
        self.generate_manifest_and_payloads()
        self.log("Pipeline finished successfully! ✨")

if __name__ == "__main__":
    compiler = ContentCompiler(CONTENT_DIR, DB_OUTPUT_PATH, STATS_OUTPUT_PATH)
    try:
        compiler.compile()
    except Exception as e:
        print(f"[NexVora Error] {e}", file=sys.stderr)
        sys.exit(1)
