#!/usr/bin/env python3
"""
NexVora Encyclopedia - 100,000+ Article Scalability & Stress Benchmark
Generates a temporary synthetic dataset of up to 100,000 structured articles across diverse categories,
measures discovery, parsing, validation, compilation, FTS indexing, database size, memory usage,
integrity, Bengali/English search latency, and category aggregation.
All temporary synthetic data is completely and safely cleaned up after testing.
"""

import os
import sys
import shutil
import tempfile
import time
import sqlite3
import tracemalloc
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from generate_encyclopedia_db import ContentCompiler

CATEGORIES = [
    ("Science", ["Physics", "Chemistry", "Biology", "Astronomy", "Earth-Science", "Mathematics"]),
    ("History", ["Ancient", "Medieval", "Modern", "World-History", "Bangladesh-History"]),
    ("Geography", ["Countries", "Cities", "Rivers", "Mountains", "Oceans", "Continents"]),
    ("Biography", ["Scientists", "Inventors", "Writers", "Artists", "Explorers", "Leaders"]),
    ("Animals", ["Mammals", "Birds", "Reptiles", "Fish", "Insects", "Marine-Life"]),
    ("Technology", ["Computers", "Internet", "Programming", "Artificial-Intelligence", "Electronics", "Software", "Cybersecurity"]),
    ("Bangladesh", ["Districts", "History", "Culture", "Geography", "Rivers", "Heritage", "Constitution"]),
    ("Literature", ["Bengali", "World", "Poetry", "Fiction", "Writers", "Drama"]),
    ("Environment", ["Climate", "Ecosystems", "Conservation", "Natural-Resources", "Energy"]),
    ("Economics", ["Banking", "Finance", "Markets", "Economic-Concepts", "Global-Economy"]),
    ("Psychology", ["Cognitive", "Behavioral", "Mental-Health"]),
    ("Arts-Culture", ["Painting", "Music", "Architecture", "Theatre"]),
    ("Sports", ["Cricket", "Football", "Olympics", "Traditional"]),
    ("Agriculture", ["Crops", "Farming-Techniques", "Fisheries", "Livestock"]),
    ("Space", ["Solar-System", "Astrophysics", "Space-Exploration"])
]

SAMPLE_TOPICS = [
    ("মহাকর্ষ", "gravity", "মহাকর্ষ হলো যেকোনো দুটি ভরযুক্ত বস্তুর মধ্যকার আকর্ষণ বল।", ["বিজ্ঞান", "পদার্থবিদ্যা"]),
    ("আপেক্ষিকতা", "relativity", "আলবার্ট আইনস্টাইন কর্তৃক প্রস্তাবিত সাধারণ ও বিশেষ আপেক্ষিকতা তত্ত্ব।", ["বিজ্ঞান", "পদার্থবিদ্যা", "পদার্থবিজ্ঞান"]),
    ("কোয়ান্টাম বলবিদ্যা", "quantum-mechanics", "পারমাণবিক এবং অতিপারমাণবিক স্কেলে পদার্থের আচরণ ব্যাখ্যার বিজ্ঞান।", ["কোয়ান্টাম", "বিজ্ঞান"]),
    ("রয়্যাল বেঙ্গল টাইগার", "royal-bengal-tiger", "বাংলাদেশের জাতীয় পশু এবং সুন্দরবনের প্রধান আকর্ষণ।", ["প্রাণী", "বাংলাদেশ", "স্তন্যপায়ী"]),
    ("সুন্দরবন", "sundarbans", "বিশ্বের বৃহত্তম ম্যানগ্রোভ বনভূমি যা বাংলাদেশ ও ভারতে বিস্তৃত।", ["বাংলাদেশ", "ভূগোল", "পরিবেশ"]),
    ("জগদীশ চন্দ্র বসু", "jagadish-chandra-bose", "বিশিষ্ট বাঙালি বিজ্ঞানী যিনি উদ্ভিদের সংবেদনশীলতা ও রেডিও তরঙ্গ নিয়ে গবেষণা করেন।", ["জীবনী", "বিজ্ঞানী", "বাঙালি"]),
    ("কম্পিউটার প্রোগ্রামিং", "computer-programming", "কম্পিউটারকে নির্দিষ্ট কার্য সম্পাদনের নির্দেশাবলী রচনার প্রক্রিয়া।", ["প্রযুক্তি", "কম্পিউটার", "সফটওয়্যার"]),
    ("কৃত্রিম বুদ্ধিমত্তা", "artificial-intelligence", "মেশিন বা সফটওয়্যার দ্বারা মানুষের বুদ্ধিমত্তা অনুকরণের বিজ্ঞান ও প্রযুক্তি।", ["প্রযুক্তি", "এআই", "রোবোটিক্স"]),
    ("সৌরজগৎ", "solar-system", "সূর্য এবং এর চারপাশে প্রদক্ষিণরত সকল গ্রহ, উপগ্রহ ও গ্রহাণুপুঞ্জের ব্যবস্থা।", ["মহাকাশ", "জ্যোতির্বিদ্যা", "বিজ্ঞান"]),
    ("পদ্মা নদী", "padma-river", "বাংলাদেশের অন্যতম প্রধান এবং আন্তর্জাতিক নদী যা হিমালয় থেকে উৎপন্ন।", ["বাংলাদেশ", "নদী", "ভূগোল"]),
    ("ডিএনএ এবং জেনেটিক্স", "dna-genetics", "জীবদেহের গঠন ও বৈশিষ্ট্যের জিনগত নির্দেশাবলী বহনকারী নিউক্লিক অ্যাসিড।", ["জীববিজ্ঞান", "বিজ্ঞান", "জেনেটিক্স"]),
    ("নবায়নযোগ্য শক্তি", "renewable-energy", "প্রাকৃতিক উৎস যেমন সূর্য, বায়ু ও পানি থেকে প্রাপ্ত টেকসই পরিবেশবান্ধব শক্তি।", ["পরিবেশ", "শক্তি", "প্রযুক্তি"])
]

def generate_synthetic_articles(target_dir, total_count=100000):
    print(f"[Benchmark] Generating {total_count:,} synthetic test articles...")
    start_time = time.time()
    
    # Flatten subcategories
    flat_paths = []
    for main_cat, sub_cats in CATEGORIES:
        for sub in sub_cats:
            cat_path = target_dir / main_cat / sub
            cat_path.mkdir(parents=True, exist_ok=True)
            flat_paths.append(cat_path)
    
    article_idx = 0
    while article_idx < total_count:
        topic_title, topic_slug, topic_summary, topic_tags = SAMPLE_TOPICS[article_idx % len(SAMPLE_TOPICS)]
        cat_folder = flat_paths[article_idx % len(flat_paths)]
        
        art_id = f"bench-{topic_slug}-{article_idx + 1}"
        title = f"{topic_title} - পর্ব {article_idx + 1}"
        
        tags_yaml = "\n".join([f"  - {t}" for t in topic_tags])
        
        # Link to another article to test internal cross-references
        ref_target_idx = (article_idx + 1) % total_count + 1
        ref_slug = SAMPLE_TOPICS[ref_target_idx % len(SAMPLE_TOPICS)][1]
        
        content = f"""---
id: {art_id}
title: {title}
tags:
{tags_yaml}
summary: {topic_summary} এটি পরীক্ষামূলক বেঞ্চমার্ক সূচক নম্বর {article_idx + 1}।
related:
  - bench-{ref_slug}-{ref_target_idx}
---

# {title}

## সংক্ষিপ্ত পরিচিতি
{topic_summary} এটি পরীক্ষামূলক বেঞ্চমার্ক সূচক নম্বর {article_idx + 1} যা [সম্পর্কিত প্রবন্ধ](article:bench-{ref_slug}-{ref_target_idx})-এর সাথে সংযুক্ত।

## সূচিপত্র
1. পরিচিতি
2. বিশদ বিবরণ
3. গুরুত্ব
4. সম্পর্কিত বিষয়
5. উপসংহার

## পরিচিতি
{topic_summary} এটি বিস্তারিত বিশ্লেষণের একটি অংশ।

## বিশদ বিবরণ
প্রাকৃতিক বিজ্ঞান, প্রযুক্তি ও ঐতিহাসিক দৃষ্টিকোণ থেকে এই বিষয়ের গুরুত্ব অপরিসীম।

## গুরুত্ব
শিক্ষার্থী ও গবেষকদের জন্য সহায়ক বিস্তারিত বিবরণী।

## সম্পর্কিত বিষয়
- [পরবর্তী অধ্যায়](article:bench-{ref_slug}-{ref_target_idx})

## উপসংহার
এই নিবন্ধটি স্বয়ংক্রিয় স্কেলেবিলিটি পরীক্ষা ও পারফরম্যান্স পরিমাপের অংশ।
"""
        file_path = cat_folder / f"article_{article_idx + 1}.md"
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(content)
        
        article_idx += 1
        if article_idx % 25000 == 0 or (total_count < 25000 and article_idx % 5000 == 0):
            print(f"[Benchmark] Generated {article_idx:,} / {total_count:,} articles ({time.time() - start_time:.2f}s)...")
            
    gen_duration = time.time() - start_time
    print(f"[Benchmark] Successfully created {total_count:,} articles in {gen_duration:.2f} seconds.")
    return gen_duration

def run_stress_test(total_count=100000):
    print("==========================================================")
    print(f"  NexVora Encyclopedia: {total_count:,} Articles Scalability Test")
    print("==========================================================")
    
    temp_dir = tempfile.mkdtemp(prefix="nexvora_benchmark_")
    bench_content_dir = Path(temp_dir) / "content"
    bench_db_path = Path(temp_dir) / "bench_encyclopedia.db"
    bench_stats_path = Path(temp_dir) / "bench_content_stats.json"
    
    tracemalloc.start()
    
    try:
        # 1. Generate synthetic dataset
        gen_duration = generate_synthetic_articles(bench_content_dir, total_count)
        
        # 2. Measure Discovery, Validation, and Compilation
        print(f"\n[Benchmark] Running Content Compiler on {total_count:,} articles...")
        compiler = ContentCompiler(bench_content_dir, bench_db_path, bench_stats_path)
        
        t0 = time.time()
        compiler.discover_categories()
        t_discovery_cat = time.time() - t0
        
        t1 = time.time()
        compiler.process_articles()
        t_process_articles = time.time() - t1
        
        t2 = time.time()
        compiler.validate()
        t_validate = time.time() - t2
        
        t3 = time.time()
        compiler.generate_database()
        t_db_gen = time.time() - t3
        
        total_pipeline_time = time.time() - t0
        current_mem, peak_mem = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        
        print(f"[Benchmark] ✅ Total Pipeline Duration: {total_pipeline_time:.2f} seconds")
        print(f"  - Category Discovery: {t_discovery_cat:.4f} s")
        print(f"  - Article Discovery & Markdown/YAML Parsing: {t_process_articles:.2f} s")
        print(f"  - Content & Link Validation: {t_validate:.2f} s")
        print(f"  - SQLite Batch Insertion & FTS Generation: {t_db_gen:.2f} s")
        print(f"  - Peak Memory Usage: {peak_mem / (1024 * 1024):.2f} MB")
        
        # 3. Database Size & Integrity Checks
        db_size_mb = bench_db_path.stat().st_size / (1024 * 1024)
        print(f"\n[Benchmark] Generated Database Size: {db_size_mb:.2f} MB")
        
        conn = sqlite3.connect(str(bench_db_path))
        cursor = conn.cursor()
        
        t_int_start = time.time()
        cursor.execute("PRAGMA integrity_check;")
        integrity = cursor.fetchone()[0]
        t_int_duration = time.time() - t_int_start
        assert integrity == "ok", f"Integrity check failed: {integrity}"
        print(f"[Benchmark] ✅ SQLite Integrity Check: PASS (Result: {integrity}, verified in {t_int_duration:.2f}s)")
        
        cursor.execute("SELECT COUNT(*) FROM articles;")
        article_count = cursor.fetchone()[0]
        assert article_count == total_count, f"Expected {total_count} articles, got {article_count}"
        print(f"[Benchmark] ✅ Total Articles Verified: {article_count:,}")
        
        cursor.execute("SELECT COUNT(*) FROM categories;")
        cat_count = cursor.fetchone()[0]
        print(f"[Benchmark] ✅ Categories Created: {cat_count:,}")
        
        cursor.execute("SELECT COUNT(*) FROM sections;")
        sec_count = cursor.fetchone()[0]
        print(f"[Benchmark] ✅ Sections Extracted: {sec_count:,}")
        
        cursor.execute("SELECT COUNT(*) FROM articles_fts;")
        fts_count = cursor.fetchone()[0]
        assert fts_count == total_count, f"Expected {total_count} FTS records, got {fts_count}"
        print(f"[Benchmark] ✅ Total FTS Indexed Records: {fts_count:,} (100% coverage)")
        
        # 4. Search Latency Benchmark
        test_queries = [
            ("মহাকর্ষ", "Bengali"),
            ("gravity", "English"),
            ("আইনস্টাইন", "Bengali"),
            ("বিজ্ঞান", "Bengali"),
            ("সুন্দরবন", "Bengali"),
            ("প্রযুক্তি", "Bengali"),
            ("বাঙালি", "Bengali"),
            ("solar", "English"),
            ("জেনেটিক্স", "Bengali"),
            ("renewable", "English")
        ]
        print(f"\n[Benchmark] Testing FTS Search Latencies across {total_count:,} articles:")
        for q, lang in test_queries:
            q_start = time.time()
            cursor.execute("""
                SELECT a.id, a.title, a.summary 
                FROM articles a
                JOIN articles_fts fts ON a.id = fts.id
                WHERE articles_fts MATCH ?
                LIMIT 20
            """, (f"{q}*",))
            rows = cursor.fetchall()
            q_time_ms = (time.time() - q_start) * 1000
            print(f"  - [{lang}] Query '{q}': {len(rows)} results in {q_time_ms:.2f} ms")
            assert q_time_ms < 50.0, f"Query '{q}' was too slow ({q_time_ms:.2f} ms)"
        
        # 5. Category Aggregations Benchmark
        cat_start = time.time()
        cursor.execute("""
            SELECT c.id, c.name, COUNT(a.id) as count
            FROM categories c
            LEFT JOIN articles a ON a.categoryId = c.id
            GROUP BY c.id
            LIMIT 50
        """)
        cat_rows = cursor.fetchall()
        cat_time_ms = (time.time() - cat_start) * 1000
        print(f"\n[Benchmark] Category aggregation across {len(cat_rows)} categories in {cat_time_ms:.2f} ms")
        
        # 6. Reading Single Article & Sections Benchmark
        art_fetch_start = time.time()
        cursor.execute("SELECT * FROM articles WHERE id = ? LIMIT 1", (f"bench-gravity-1",))
        art_row = cursor.fetchone()
        cursor.execute("SELECT * FROM sections WHERE articleId = ? ORDER BY position ASC", (f"bench-gravity-1",))
        sec_rows = cursor.fetchall()
        art_fetch_ms = (time.time() - art_fetch_start) * 1000
        print(f"[Benchmark] Direct Article Reader Fetch (Article + {len(sec_rows)} Sections): {art_fetch_ms:.2f} ms")
        
        conn.close()
        print("\n==========================================================")
        print(f"  🎉 ALL {total_count:,} ARTICLE SCALABILITY BENCHMARKS PASSED! 🎉")
        print("==========================================================")
        
    finally:
        # Cleanup temporary files completely
        print("[Benchmark] Cleaning up temporary benchmark artifacts...")
        shutil.rmtree(temp_dir, ignore_errors=True)
        print("[Benchmark] Cleanup complete. Zero synthetic files remaining.")

if __name__ == "__main__":
    count = 100000
    if len(sys.argv) > 1:
        try:
            count = int(sys.argv[1])
        except ValueError:
            pass
    run_stress_test(count)

