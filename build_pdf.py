#!/usr/bin/env python3
"""
Build a PDF of the Algorithmica "Algorithms for Modern Hardware" book.
Collects Markdown files in chapter/section order, pre-processes them,
and feeds them to pandoc → XeLaTeX → PDF.
"""

import os
import re
import subprocess
import shutil
import sys
import yaml
import tempfile

CONTENT_DIR = os.path.join(os.path.dirname(__file__), "content", "english", "hpc")
OUTPUT_PDF  = os.path.join(os.path.dirname(__file__), "algorithmica-hpc.pdf")
SCRATCH_DIR = tempfile.mkdtemp(prefix="algo-pdf-")

# Chapters with weight >= 100 are future/incomplete parts – skip them.
MAX_CHAPTER_WEIGHT = 99

# ──────────────────────────────────────────────
# 1. Read YAML frontmatter from a markdown file
# ──────────────────────────────────────────────
def read_frontmatter(path):
    """Return (meta_dict, body_str) for a markdown file."""
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    if not raw.startswith("---"):
        return {}, raw
    end = raw.find("\n---", 3)
    if end == -1:
        return {}, raw
    fm_text = raw[3:end].strip()
    body    = raw[end + 4:].lstrip("\n")
    try:
        meta = yaml.safe_load(fm_text) or {}
    except yaml.YAMLError:
        meta = {}
    return meta, body


# ──────────────────────────────────────────────
# 2. Collect files in chapter → section order
# ──────────────────────────────────────────────
def collect_files():
    """
    Returns a list of (chapter_weight, section_weight, filepath) tuples,
    sorted so chapters come in order and sections within a chapter come in order.
    The chapter _index.md itself is listed first (section_weight = -999).
    The preface.md at the hpc root is listed very first (chapter_weight = -1).
    """
    entries = []

    # Preface at the hpc root level
    preface = os.path.join(CONTENT_DIR, "preface.md")
    if os.path.exists(preface):
        meta, _ = read_frontmatter(preface)
        w = meta.get("weight", 0)
        entries.append((w, -999, preface))

    # Chapter _index.md at root
    root_index = os.path.join(CONTENT_DIR, "_index.md")
    if os.path.exists(root_index):
        meta, _ = read_frontmatter(root_index)
        # Treat book intro as chapter 0
        entries.append((0, -999, root_index))

    # Walk chapter directories
    for chapter_name in os.listdir(CONTENT_DIR):
        chapter_dir = os.path.join(CONTENT_DIR, chapter_name)
        if not os.path.isdir(chapter_dir):
            continue

        chapter_index = os.path.join(chapter_dir, "_index.md")
        if not os.path.exists(chapter_index):
            continue

        ch_meta, _ = read_frontmatter(chapter_index)
        ch_weight  = ch_meta.get("weight", 999)

        if ch_weight > MAX_CHAPTER_WEIGHT:
            print(f"  Skipping chapter '{chapter_name}' (weight {ch_weight} > {MAX_CHAPTER_WEIGHT})")
            continue

        # Chapter index itself
        entries.append((ch_weight, -999, chapter_index))

        # Sections within the chapter
        for fname in os.listdir(chapter_dir):
            if fname == "_index.md" or not fname.endswith(".md"):
                continue
            fpath = os.path.join(chapter_dir, fname)
            sec_meta, _ = read_frontmatter(fpath)

            # Skip draft files
            if sec_meta.get("draft", False):
                print(f"  Skipping draft: {fpath}")
                continue

            sec_weight = sec_meta.get("weight", 500)
            entries.append((ch_weight, sec_weight, fpath))

        # Walk one level of sub-directories (e.g. parallel/algorithms/)
        for sub in os.listdir(chapter_dir):
            subdir = os.path.join(chapter_dir, sub)
            if not os.path.isdir(subdir):
                continue
            sub_index = os.path.join(subdir, "_index.md")
            if not os.path.exists(sub_index):
                continue
            sub_meta, _ = read_frontmatter(sub_index)
            sub_weight  = sub_meta.get("weight", 500)
            entries.append((ch_weight, sub_weight - 0.5, sub_index))
            for fname in os.listdir(subdir):
                if fname == "_index.md" or not fname.endswith(".md"):
                    continue
                fpath = os.path.join(subdir, fname)
                sec_meta, _ = read_frontmatter(fpath)
                if sec_meta.get("draft", False):
                    continue
                sec_weight = sec_meta.get("weight", 500)
                entries.append((ch_weight, sub_weight + sec_weight * 0.01, fpath))

    entries.sort(key=lambda t: (t[0], t[1]))
    return [e[2] for e in entries]


# ──────────────────────────────────────────────
# 3. Convert SVG / PPM images to PNG
# ──────────────────────────────────────────────
IMG_CACHE = {}   # original_path → converted_path (or same if no conversion needed)

def convert_image(src_path):
    """Return a path suitable for XeLaTeX (PNG or JPG). Convert SVG/PPM if needed."""
    if src_path in IMG_CACHE:
        return IMG_CACHE[src_path]
    if not os.path.exists(src_path):
        IMG_CACHE[src_path] = src_path
        return src_path
    ext = os.path.splitext(src_path)[1].lower()
    if ext in (".png", ".jpg", ".jpeg", ".pdf"):
        IMG_CACHE[src_path] = src_path
        return src_path
    # Need conversion
    safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", os.path.relpath(src_path, CONTENT_DIR))
    out_path = os.path.join(SCRATCH_DIR, safe_name + ".png")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    if ext == ".svg":
        try:
            subprocess.run(
                ["rsvg-convert", "-w", "1200", "-f", "png", "-o", out_path, src_path],
                check=True, capture_output=True
            )
        except subprocess.CalledProcessError:
            IMG_CACHE[src_path] = src_path
            return src_path
    elif ext in (".ppm", ".webp", ".bmp", ".gif", ".tiff", ".tif"):
        # Use Pillow for formats XeLaTeX can't handle
        try:
            from PIL import Image
            img = Image.open(src_path).convert("RGB")
            img.save(out_path, "PNG")
        except Exception as e:
            print(f"  Warning: could not convert {src_path}: {e}")
            IMG_CACHE[src_path] = src_path
            return src_path
    else:
        IMG_CACHE[src_path] = src_path
        return src_path
    IMG_CACHE[src_path] = out_path
    return out_path


# ──────────────────────────────────────────────
# 4. Pre-process markdown body
# ──────────────────────────────────────────────
def resolve_image_path(img_ref, md_dir):
    """Turn a relative image reference into an absolute path."""
    if img_ref.startswith("http://") or img_ref.startswith("https://"):
        return None   # skip external images
    # Hugo static files live in /static/
    if img_ref.startswith("/"):
        candidate = os.path.join(os.path.dirname(CONTENT_DIR), "..", "static", img_ref.lstrip("/"))
        candidate = os.path.normpath(candidate)
        if os.path.exists(candidate):
            return candidate
        # Also try the themes/algorithmica/static
        for theme_dir in ["themes/algorithmica/static", "static"]:
            candidate2 = os.path.join(os.path.dirname(CONTENT_DIR), "..", theme_dir, img_ref.lstrip("/"))
            candidate2 = os.path.normpath(candidate2)
            if os.path.exists(candidate2):
                return candidate2
        return None
    abs_path = os.path.normpath(os.path.join(md_dir, img_ref))
    if os.path.exists(abs_path):
        return abs_path
    return None


def fix_math_blocks(text):
    """
    Post-process the full combined text to fix LaTeX math issues that arise
    when Hugo-flavoured markdown is fed to pandoc.

    Known issues:
      1. Hugo uses \\\\% to mean a literal percent sign in math.
         Pandoc keeps the double-backslash, giving \\\\% in LaTeX which
         means "line-break then comment" -> Missing $ error.
         Fix: collapse \\\\% -> \\% inside $...$ / $$...$$ spans.

      2. \\begin{cases} blocks use && as a description separator.
         Standard amsmath cases only allows a single &.
         Fix: replace && with & inside math fences that contain \\begin{cases}.
    """
    # fix 1: \\% inside math inline and display
    text = text.replace('\\\\%', '\\%')

    # fix 2: alignment issues inside \begin{cases}...\end{cases}
    def fix_cases(m):
        block = m.group(0)
        # First collapse "&&" -> "&"
        block = block.replace('&&', '&')
        # Now fix any remaining lines that still have more than one "&"
        fixed_lines = []
        for line in block.split('\n'):
            parts = line.split('&')
            if len(parts) > 2:
                # Keep first separator, join the rest with \quad
                line = parts[0] + '&' + r'\quad '.join(parts[1:])
            fixed_lines.append(line)
        return '\n'.join(fixed_lines)

    text = re.sub(
        r'\\begin\{cases\}.*?\\end\{cases\}',
        fix_cases,
        text,
        flags=re.DOTALL,
    )

    return text


def preprocess(body, md_dir, title, is_chapter_index=False):
    """Clean up markdown body for pandoc."""
    lines = body.split("\n")
    out   = []

    # Add a section/chapter heading derived from the title
    if title:
        level = "#" if is_chapter_index else "##"
        out.append(f"{level} {title}\n")

    for line in lines:
        # Strip Hugo shortcodes  {{< ... >}}  and  {{% ... %}}
        line = re.sub(r'\{\{[<%].*?[>%]\}\}', '', line)

        # Fix image references: ![alt](path)
        def fix_img(m):
            alt  = m.group(1)
            ref  = m.group(2).split(" ")[0]   # strip optional title after space
            abs_p = resolve_image_path(ref, md_dir)
            if abs_p is None:
                return ""   # remove broken image refs
            converted = convert_image(abs_p)
            return f"![{alt}]({converted})"

        line = re.sub(r'!\[([^\]]*)\]\(([^)]+)\)', fix_img, line)

        # Convert Hugo internal links to plain text (keep label)
        # e.g. [cache lines](../cache-lines) -> cache lines
        line = re.sub(r'\[([^\]]+)\]\((?!http)[^)]+\)', r'\1', line)

        out.append(line)

    return "\n".join(out)


# ──────────────────────────────────────────────
# 5. Combine all files into one big markdown
# ──────────────────────────────────────────────
def build_combined_markdown(file_list):
    parts = []
    for path in file_list:
        meta, body = read_frontmatter(path)
        title      = meta.get("title", "")
        md_dir     = os.path.dirname(path)
        is_index   = os.path.basename(path) == "_index.md"

        processed  = preprocess(body, md_dir, title, is_chapter_index=is_index)
        if processed.strip():
            parts.append(processed)
            parts.append("\n\n---\n\n")   # visual separator between sections

    combined = "\n".join(parts)
    combined = fix_math_blocks(combined)
    return combined


# ──────────────────────────────────────────────
# 6. Run pandoc
# ──────────────────────────────────────────────
PANDOC_HEADER = r"""---
title: "Algorithms for Modern Hardware"
author: "Sergey Slotin"
date: "2022"
lang: en
documentclass: book
classoption:
  - 11pt
  - openany
geometry:
  - margin=1in
header-includes:
  - \usepackage{amsmath}
  - \usepackage{amssymb}
  - \usepackage{unicode-math}
  - \usepackage{xcolor}
  - \usepackage{fancyvrb}
  - \usepackage{listings}
  - \usepackage{hyperref}
  - \hypersetup{colorlinks=true, linkcolor=blue, urlcolor=blue}
  - \usepackage{graphicx}
  - \setlength{\emergencystretch}{3em}
  - \providecommand{\tightlist}{\setlength{\itemsep}{0pt}\setlength{\parskip}{0pt}}
---
"""


def run_pandoc(md_path, pdf_path):
    cmd = [
        "pandoc",
        md_path,
        "--pdf-engine=xelatex",
        "--pdf-engine-opt=-interaction=nonstopmode",
        "--toc",
        "--toc-depth=2",
        "--number-sections",
        "-V", "mainfont=DejaVu Serif",
        "-V", "monofont=DejaVu Sans Mono",
        "--highlight-style=tango",
        "--wrap=none",
        "-o", pdf_path,
    ]
    print("Running pandoc ...")
    result = subprocess.run(cmd, capture_output=True, text=True)
    # Treat it as success if the PDF was written, even with minor LaTeX errors
    pdf_exists = os.path.exists(pdf_path) and os.path.getsize(pdf_path) > 0
    if result.returncode != 0 and not pdf_exists:
        print("STDERR:", result.stderr[-3000:])
        sys.exit(1)
    if result.stderr:
        warnings = [l for l in result.stderr.splitlines()
                    if l.strip() and not l.startswith("[WARNING] Could not convert image")]
        if warnings:
            print("Pandoc messages:", "\n".join(warnings[:10]))


# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────
def main():
    print(f"Collecting files from {CONTENT_DIR} ...")
    files = collect_files()
    print(f"  {len(files)} files collected")

    print("Pre-processing and combining ...")
    combined = PANDOC_HEADER + build_combined_markdown(files)

    combined_path = os.path.join(SCRATCH_DIR, "combined.md")
    with open(combined_path, "w", encoding="utf-8") as f:
        f.write(combined)
    print(f"  Written to {combined_path} ({len(combined)//1024} KB)")

    run_pandoc(combined_path, OUTPUT_PDF)

    size_kb = os.path.getsize(OUTPUT_PDF) // 1024
    print(f"\n\u2705  PDF generated: {OUTPUT_PDF}  ({size_kb} KB)")
    print(f"Temporary files in: {SCRATCH_DIR}")


if __name__ == "__main__":
    main()
