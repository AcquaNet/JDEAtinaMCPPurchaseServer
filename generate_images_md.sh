#!/usr/bin/env bash
set -euo pipefail

IMAGES_DIR="images"
OUT_FILE="IMAGES.md"

if [[ ! -d "$IMAGES_DIR" ]]; then
  echo "ERROR: Folder '$IMAGES_DIR' not found. Run this script from the project root (or create ./images)." >&2
  exit 1
fi

echo "Renaming files with spaces -> underscores in '$IMAGES_DIR'..."
for f in "$IMAGES_DIR"/*; do
  [[ -e "$f" ]] || continue
  nf="${f// /_}"
  [[ "$f" == "$nf" ]] || mv "$f" "$nf"
done

echo "Generating '$OUT_FILE' sorted by oldest first..."
# Oldest first (macOS): prefer birth time; fallback to mtime.
find "$IMAGES_DIR" -type f \( \
  -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.gif' -o -iname '*.webp' \
\) -print0 | while IFS= read -r -d '' p; do
  b=$(stat -f %B "$p" 2>/dev/null || echo 0)
  if [[ "$b" == "0" ]]; then
    b=$(stat -f %m "$p")
  fi
  printf "%s\t%s\n" "$b" "$p"
done | sort -n | cut -f2- | while IFS= read -r p; do
  base="$(basename "$p")"
  # Markdown: show filename as alt text
  printf "![%s](%s)\n" "$base" "$p"
done > "$OUT_FILE"

count=$(wc -l < "$OUT_FILE" | tr -d ' ')
echo "Done. Wrote $count image links to '$OUT_FILE'."