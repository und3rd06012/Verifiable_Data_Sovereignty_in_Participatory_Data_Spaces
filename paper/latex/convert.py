#!/usr/bin/env python3
"""Converts the drafted markdown chapters into MDPI LaTeX body content.
Deterministic script, not a rewrite: escapes special characters, converts
citations to \\cite{key}, converts headers/bold/backticks/pipe tables,
inserts figures into Chapter 5 at the points the prose already names them."""
import re
import sys

SECTIONS_DIR = "/home/und3rd06012/benefits/paper/sections"
OUT_DIR = "/home/und3rd06012/benefits/paper/latex"

# author-display-string -> bibkey, longest/most-specific first so substring
# collisions do not misfire (checked by construction below).
CITE_MAP = [
    ("Katrakazas and Ntib", "katrakazas2026"),
    ("Steinbuss et al.", "idsram4"),
    ("Gaia-X AISBL", "gaiax"),
    ("Chereja et al.", "chereja2025"),
    ("Gisdakis et al.", "gisdakis2014"),
    ("Vaughn and Jacquez", "vaughn2020"),
    ("Harrington et al.", "harrington2019"),
    ("Giorgi et al.", "giorgi2022"),
    ("DECODE Consortium", "decode2019"),
    ("Greshake Tzovaras et al.", "openhumans2019"),
    ("Langford et al.", "mydata2020"),
    ("McInnis et al.", "mcinnis2024"),
    ("Dam, Klausner, Neumaier, and Priebe", "dam2023"),
    ("Dam et al.", "dam2023"),
    ("M\u00fcgge et al.", "mugge2023"),
    ("Kalogeropoulos et al.", "kalogeropoulos2023"),
    ("Norikane and Nishimura", "norikane2025"),
    ("Albalwy", "albalwy2026"),
    ("Schneier and Kelsey", "schneier1999"),
    ("Crosby and Wallach", "crosby2009"),
    ("Laurie, Langley, and Kasper", "laurie2013"),
    ("Laurie et al.", "laurie2013"),
    ("Melara et al.", "melara2015"),
    ("Hu et al.", "hu2021"),
    ("Abadi et al.", "abadi2016"),
    ("Mironov", "mironov2017"),
    ("Hay et al.", "hay2016"),
    ("Rogers et al.", "rogers2021"),
    ("Abowd et al.", "abowd2022"),
    ("Prasser et al.", "prasser2020"),
    ("Kokoris-Kogias et al.", "kokoriskogias2020"),
    ("Raisaro et al.", "raisaro2019"),
    ("Zhao and Su", "zhao2024"),
]

CITE_TOKENS = {}


def stash_citations(text):
    """Replace every recognized citation form with a unique placeholder
    token BEFORE LaTeX-escaping runs, so escaping never touches \\cite{}."""
    global CITE_TOKENS
    idx = 0

    def repl_paren(m):
        nonlocal idx
        name = m.group(1)
        for disp, key in CITE_MAP:
            if disp == name:
                token = f"@@CITE{idx}@@"
                CITE_TOKENS[token] = f"\\cite{{{key}}}"
                idx += 1
                return token
        return m.group(0)

    def repl_narrative(m):
        nonlocal idx
        name = m.group(1)
        for disp, key in CITE_MAP:
            if disp == name:
                token = f"@@CITE{idx}@@"
                CITE_TOKENS[token] = f"\\cite{{{key}}}"
                idx += 1
                return token
        return m.group(0)

    # Parenthetical form: (Name, YYYY)
    names_sorted = sorted(set(d for d, k in CITE_MAP), key=len, reverse=True)
    alt = "|".join(re.escape(n) for n in names_sorted)
    text = re.sub(rf"\(({alt}),\s*\d{{4}}\)", repl_paren, text)
    # Narrative form: Name (YYYY) or Name's (YYYY)  -- keep "Name" word, drop " (YYYY)"
    text = re.sub(rf"({alt})\s*\(\d{{4}}\)", repl_narrative, text)
    return text


def restore_citations(text):
    for token, cite in CITE_TOKENS.items():
        text = text.replace(token, cite)
    return text


def stash_code(text):
    """Replace `backtick code` with placeholders before escaping."""
    tokens = {}
    idx = [0]

    def repl(m):
        token = f"@@CODE{idx[0]}@@"
        content = m.group(1)
        escaped = escape_tex(content, in_code=True)
        tokens[token] = f"\\texttt{{{escaped}}}"
        idx[0] += 1
        return token

    text = re.sub(r"`([^`]+)`", repl, text)
    return text, tokens


def stash_math(text):
    """Replace genuine $...$ LaTeX math spans with placeholders before
    escaping, so the escaper's blanket $ -> \\$ never touches real math
    a drafting pass wrote directly (e.g. $\\varepsilon = 0.5$)."""
    tokens = {}
    idx = [0]

    def repl(m):
        token = f"@@MATH{idx[0]}@@"
        tokens[token] = m.group(0)
        idx[0] += 1
        return token

    text = re.sub(r"\$[^$\n]+\$", repl, text)
    return text, tokens


def escape_tex(text, in_code=False):
    # Unicode minus sign -> plain hyphen-minus; LaTeX text mode has no
    # native glyph for U+2212 without extra packages.
    text = text.replace("−", "-")
    text = text.replace("&", r"\&")
    text = text.replace("%", r"\%")
    text = text.replace("#", r"\#")
    text = text.replace("_", r"\_")
    if not in_code:
        text = text.replace("$", r"\$")
    return text


def convert_bold(text):
    return re.sub(r"\*\*(.+?)\*\*", r"\\textbf{\1}", text)


def convert_headers(text):
    """Normalizes headers regardless of literal markdown depth: each file's
    OWN top-level heading (whatever #-depth it uses) becomes \\section, and
    one level deeper becomes \\subsection. Chapters 2 and 3 use ##/### while
    the rest use #/##; both must map to the same LaTeX levels."""
    lines = text.split("\n")
    top_depth = None
    for line in lines:
        m = re.match(r"^(#{1,4})\s+[\d.]+\.?\s*(.+)$", line)
        if m:
            top_depth = len(m.group(1))
            break
    out = []
    for line in lines:
        m = re.match(r"^(#{1,4})\s+[\d.]+\.?\s*(.+)$", line)
        if m:
            depth = len(m.group(1))
            title = m.group(2)
            if depth == top_depth:
                out.append(f"\\section{{{title}}}")
            else:
                out.append(f"\\subsection{{{title}}}")
            continue
        out.append(line)
    return "\n".join(out)


TABLE_CAPTION_RE = re.compile(r"^Table (\d+)\.\s+(.+)$")
TABLE_WIDE_COLUMN_CHARS = 35


def _split_table_row(line):
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    return [c.strip() for c in line.split("|")]


def _column_alignment(sep_cell):
    sep_cell = sep_cell.strip()
    left = sep_cell.startswith(":")
    right = sep_cell.endswith(":")
    if left and right:
        return "c"
    if right:
        return "r"
    return "l"


def convert_tables(text):
    """Converts a `Table N. caption` paragraph immediately followed by a
    markdown pipe table into a booktabs LaTeX table environment. Runs after
    citations/math/code/escaping/bold, so cell text is already LaTeX-ready;
    splitting on `|` is safe because authored table cells never contain a
    literal pipe character."""
    lines = text.split("\n")
    out = []
    i, n = 0, len(lines)
    while i < n:
        m = TABLE_CAPTION_RE.match(lines[i].strip())
        j = i + 1
        while m and j < n and lines[j].strip() == "":
            j += 1
        is_table_start = (
            m and j < n and lines[j].strip().startswith("|")
            and j + 1 < n and re.match(r"^\|?[\s:|-]+$", lines[j + 1].strip())
        )
        if is_table_start:
            table_num, caption = m.group(1), m.group(2)
            header = _split_table_row(lines[j])
            aligns = [_column_alignment(c) for c in _split_table_row(lines[j + 1])]
            k = j + 2
            rows = []
            while k < n and lines[k].strip().startswith("|"):
                rows.append(_split_table_row(lines[k]))
                k += 1
            widest = [
                max([len(header[c])] + [len(r[c]) for r in rows])
                for c in range(len(header))
            ]
            spec = "".join(
                "X" if w > TABLE_WIDE_COLUMN_CHARS else a
                for w, a in zip(widest, aligns)
            )
            use_x = "X" in spec
            env = "tabularx" if use_x else "tabular"
            begin_tabular = (
                f"\\begin{{tabularx}}{{\\textwidth}}{{{spec}}}" if use_x
                else f"\\begin{{tabular}}{{{spec}}}"
            )
            tex = [f"\\begin{{table}}[H]", f"\\caption{{{caption}\\label{{tab:t{table_num}}}}}"]
            if not use_x:
                tex.append("\\small")
            tex.append(begin_tabular)
            tex.append("\\toprule")
            tex.append(" & ".join(header) + " \\\\")
            tex.append("\\midrule")
            for r in rows:
                tex.append(" & ".join(r) + " \\\\")
            tex.append("\\bottomrule")
            tex.append(f"\\end{{{env}}}")
            tex.append("\\end{table}")
            out.append("\n".join(tex))
            i = k
            continue
        out.append(lines[i])
        i += 1
    return "\n".join(out)


FIGURE_INSERTS = {
    "Figure 1 (e1-crossover.pdf)": (
        "e1-crossover", "E1: signature-verification cost at the edge. Median wall time "
        "against batch size for the baseline, structural, and signature arms on "
        "log-log axes, one panel per field count (1, 5, 20). IQR bands shown for "
        "each arm."
    ),
    "Figure 2 (e2-propagation.pdf)": (
        "e2-propagation", "E2: revocation propagation and consistency latency against "
        "online probability, with IQR bands."
    ),
    "Figure 3 (e3-verification-cost.pdf)": (
        "e3-verification-cost", "E3: audit-chain verification cost at scale. "
        "(\\textbf{a}) Inclusion proof size against epoch size. (\\textbf{b}) "
        "Inclusion-proof verification time against epoch size. (\\textbf{c}) "
        "Chain-of-custody verification time against chain length."
    ),
    "Figure 4 (e4-utility.pdf)": (
        "e4-utility", "E4: privacy-budget-versus-utility tradeoff. Surviving median "
        "relative error against epsilon, one panel per population size, one line "
        "per k threshold, uniform skew only."
    ),
    "Figure 5 (g1-availability.pdf)": (
        "g1-availability", "G1: quorum failure rate against action probability for "
        "Arm A (the built, veto-blind policy) and Arm B (SIDS 2026's specified "
        "veto policy), one panel per quorum fraction."
    ),
    "Figure 6 (g2-heterogeneity.pdf)": (
        "g2-heterogeneity", "G2: surviving coverage error and surviving exclusion "
        "error against consent heterogeneity h, one panel per epsilon, with IQR "
        "bands."
    ),
}


def insert_figures(text):
    """For Chapter 5 only: right before the paragraph that first names a
    figure, insert the corresponding \\begin{figure} block."""
    for marker, (fname, caption) in FIGURE_INSERTS.items():
        if marker in text:
            fig_tex = (
                f"\n\\begin{{figure}}[H]\n\\centering\n"
                f"\\includegraphics[width=0.9\\linewidth]"
                f"{{../../experiments/analysis/figures/{fname}.pdf}}\n"
                f"\\caption{{{caption}}}\n"
                f"\\end{{figure}}\n\n"
            )
            idx = text.index(marker)
            # walk back to the start of the paragraph (previous blank line)
            para_start = text.rfind("\n\n", 0, idx)
            para_start = para_start + 2 if para_start != -1 else 0
            text = text[:para_start] + fig_tex + text[para_start:]
    return text


def convert_file(path, insert_figs=False):
    text = open(path).read()
    text = convert_headers(text)
    text = stash_citations(text)
    text, math_tokens = stash_math(text)
    text, code_tokens = stash_code(text)
    text = escape_tex(text)
    for token, val in code_tokens.items():
        text = text.replace(token, val)
    for token, val in math_tokens.items():
        text = text.replace(token, val)
    text = convert_bold(text)
    text = restore_citations(text)
    text = convert_tables(text)
    if insert_figs:
        text = insert_figures(text)
    return text


if __name__ == "__main__":
    files = [
        ("01-introduction.md", False),
        ("02-related-work.md", False),
        ("03-system-architecture.md", False),
        ("04-methodology.md", False),
        ("05-results.md", True),
        ("06-discussion.md", False),
        ("07-conclusion.md", False),
        ("08-future-work.md", False),
    ]
    combined = []
    for fname, figs in files:
        body = convert_file(f"{SECTIONS_DIR}/{fname}", insert_figs=figs)
        combined.append(body)
        out_name = fname.replace(".md", ".tex")
        with open(f"{OUT_DIR}/{out_name}", "w") as f:
            f.write(body)
        print(f"wrote {out_name}, {len(body)} chars")
    print(f"\nTotal citation tokens replaced: {len(CITE_TOKENS)}")
