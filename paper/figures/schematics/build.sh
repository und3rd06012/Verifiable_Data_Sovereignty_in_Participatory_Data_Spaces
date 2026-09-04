#!/usr/bin/env bash
# Build all BENEFITS schematic figures.
# Produces one cropped PDF per figure, a merged samples.pdf, and 300-dpi
# PNG previews under preview/.
set -euo pipefail
cd "$(dirname "$0")"

FIGS=(fig-system-architecture fig-enforcement-points fig-release-flow fig-privacy-pipeline)

for f in "${FIGS[@]}"; do
  pdflatex -interaction=nonstopmode -halt-on-error "$f.tex" >/dev/null
  pdflatex -interaction=nonstopmode -halt-on-error "$f.tex" >/dev/null
done

mkdir -p preview
for f in "${FIGS[@]}"; do
  pdftoppm -png -r 300 "$f.pdf" "preview/$f"
done

pdfunite "${FIGS[@]/%/.pdf}" samples.pdf
echo "built: ${FIGS[*]}"
