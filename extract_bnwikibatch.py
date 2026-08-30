#!/usr/bin/env python3

import bz2
import os
import re
import sys
import xml.etree.ElementTree as ET

INPUT = os.path.expanduser(
    "~/storage/downloads/bnwiki-2026-08-01-p4p1652969.xml.bz2"
)

OUTPUT = os.path.expanduser(
    "~/NexVora/content/Wikipedia-BN"
)

BATCH_SIZE = 50

os.makedirs(OUTPUT, exist_ok=True)

def clean_filename(title):
    title = title.strip()
    title = re.sub(r'[<>:"/\\|?*]', '_', title)
    title = re.sub(r'\s+', ' ', title)
    return title[:180] or "untitled"

def is_main_namespace(ns):
    return ns in ("", "0")

def write_article(title, text, number):
    safe = clean_filename(title)
    path = os.path.join(OUTPUT, f"{number:06d}-{safe}.md")

    with open(path, "w", encoding="utf-8") as f:
        f.write("---\n")
        f.write(f"id: bnwiki-{number:06d}\n")
        f.write(f"title: {title}\n")
        f.write("source: Bengali Wikipedia\n")
        f.write("---\n\n")
        f.write(text.strip())
        f.write("\n")

    return path

article_count = 0
batch_count = 0
batch_article_count = 0

print("Starting streaming extraction...")
print("Input:", INPUT)
print("Output:", OUTPUT)
print()

with bz2.open(INPUT, "rb") as compressed:
    for event, elem in ET.iterparse(compressed, events=("end",)):
        if not elem.tag.endswith("page"):
            continue

        title = ""
        text = ""
        namespace = ""

        for child in elem.iter():
            tag = child.tag.split("}")[-1]

            if tag == "title" and child.text:
                title = child.text

            elif tag == "ns" and child.text:
                namespace = child.text

            elif tag == "text" and child.text:
                text = child.text

        if is_main_namespace(namespace) and title and text.strip():
            article_count += 1
            batch_article_count += 1

            path = write_article(
                title,
                text,
                article_count
            )

            if batch_article_count == BATCH_SIZE:
                batch_count += 1

                print(
                    f"Batch {batch_count}: "
                    f"{batch_article_count} articles "
                    f"(total: {article_count})"
                )

                batch_article_count = 0

        elem.clear()

print()
print("Extraction finished.")
print("Articles:", article_count)
print("Batches:", batch_count)
