# LaTeX build

MDPI Systems journal template (ISSN 2079-8954), class file from the
`ihrke/mdpi` community mirror (11 March 2023 `mdpi.cls`), patched for two
real bugs found in that specific build:

1. Hyperref loading was commented out (adapted for Quarto's own loading
   path). Restored at `mdpi.cls` lines 360/364.
2. `mdpi.cls` calls `\ifnumcomp` with xifthen's 5-argument signature, but
   only etoolbox's incompatible 3-argument `\ifnumcomp` is ever loaded.
   `manuscript.tex` redefines it compatibly before `\firstpage`.

`sections/*.tex` are generated from `../sections/*.md` by `convert.py`
(header normalization across the two heading-depth conventions the
drafting chapters used, citation-key mapping to `references.bib`,
LaTeX escaping with real `$...$` math and `` `code` `` spans protected,
figure placement in Results). Re-run `python3 convert.py` after editing
the source Markdown, then rebuild.

## Build

```
pdflatex -interaction=nonstopmode -shell-escape manuscript.tex
bibtex manuscript
pdflatex -interaction=nonstopmode -shell-escape manuscript.tex
pdflatex -interaction=nonstopmode -shell-escape manuscript.tex
```

`-shell-escape` is required once, for `epstopdf` to convert the MDPI logo
assets under `pics/` (already pre-converted to PDF in this repo, so plain
`pdflatex` without `-shell-escape` also works).

## Known placeholders to fill in before real submission

- `\doinum`, `\articlenumber`, `\pubvolume`, `\issuenum`, dates, and the
  academic editor field in `manuscript.tex` are all placeholders (assigned
  by MDPI's editorial system at acceptance).
- `\dataavailability` states the repository is available on request and
  intended for public release; add the real URL once one exists.
- Six bibliography entries carry `and others` in place of a full author
  list (`chereja2025`, `latupeirissa2024`, `abbas2018`, `bozorgmehr2023`,
  `craig2025`, `mydata2020`); MDPI requires full author lists.
- The EU Data Governance Act and Data Act (`dga2022`, `dataact2023`) are
  named in Related Work without an in-text citation; wire them in or drop
  the unused bib entries.
