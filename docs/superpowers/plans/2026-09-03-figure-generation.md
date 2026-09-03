# Figure Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `experiments/analysis/`, the Python figure-generation pipeline that turns all six phase-7 experiments' already-committed CSVs into seven reportable figures.

**Architecture:** A Python venv with matplotlib and pandas as the only two third-party dependencies. A shared `common.py` for plot styling and CSV loading. One script per experiment (E3 combining its three CSVs into one figure), each independently runnable and producing a PDF+PNG pair. A `generate_all.py` driver runs every script in sequence.

**Tech Stack:** Python 3.12 (confirmed present at `python3`), matplotlib 3.11.1, pandas 3.0.5 (exact versions resolved and confirmed installable at plan-writing time), stdlib `unittest` for tests (no pytest, keeping the dependency count at exactly two, matching this repository's own established minimalism).

**Spec:** `docs/superpowers/specs/2026-09-03-figure-generation-design.md`

## Global Constraints

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code, logs, or figures.
- Every figure regenerable from the already-committed CSVs alone: no network access, no live infrastructure, no randomness of any kind.
- Every relative import inside `experiments/analysis/` (e.g. `from common import ...`) assumes scripts are run with `experiments/analysis/` as the working directory; every command in this plan reflects that.
- Two data sources exist in this repo's CSVs: raw per-trial rows needing this pipeline's own median/IQR aggregation (E1's three CSVs, E3's `proof-size.csv` and `verify-inclusion-time.csv`), and already-aggregated rows carrying their own `median`/`iqr` columns (E2, E4, G1, G2, and E3's `verify-chain-time.csv`). For the first kind, IQR shading uses real `.quantile(0.25)`/`.quantile(0.75)` computed directly from the raw data. For the second kind, only a single `iqr` column exists (not separate q25/q75), so shading uses the standard symmetric approximation `median ± iqr / 2`. Every script that uses the second convention says so in a comment, since the two are visibly different and this is a deliberate choice, not an inconsistency.
- E4 and G2's figures use the `surviving_*` statistic columns specifically (`surviving_median_relative_error`, `surviving_iqr_relative_error`, and the G2 equivalents), never the plain `median_relative_error`/`coverage_median_relative_error`/etc. columns, which this session's own final-review fix waves added the surviving-only pair specifically to route around (the plain columns are pinned at a sentinel value across most of both swept grids).

---

## Task 1: Python environment, `common.py`, and its tests

**Files:**
- Create: `experiments/analysis/requirements.txt`
- Create: `experiments/analysis/common.py`
- Test: `experiments/analysis/test_common.py`

**Interfaces:**
- Produces: `common.COLORS` (a list of 7 hex color strings), `common.setup_plot_style(): None`, `common.load_csv(path: str) -> pandas.DataFrame`. Used by every later task.

- [ ] **Step 1: Create the venv and install dependencies**

```bash
cd experiments/analysis
python3 -m venv .venv
.venv/bin/pip install matplotlib pandas
.venv/bin/pip freeze | grep -iE '^(matplotlib|pandas|numpy)' > requirements.txt
```

Confirm `requirements.txt` now contains three lines pinning `matplotlib==3.11.1`,
`numpy==2.5.2`, and `pandas==3.0.5` (the exact versions confirmed installable at
plan-writing time; if a different version resolves in your environment, that is
fine, use whatever `pip freeze` actually reports rather than hand-editing to
match the versions above).

- [ ] **Step 2: Write `common.py`**

`experiments/analysis/common.py`:

```python
"""Shared plotting style and CSV-loading helpers for phase 7's figure scripts.

Every experiment's own figure script calls setup_plot_style() once before
creating any figure, and load_csv() to read a results CSV. Kept in one file
since neither helper is large enough to need its own module.
"""
import matplotlib.pyplot as plt
import pandas as pd

# A fixed, colorblind-safe categorical color sequence (Okabe-Ito), reused
# across every figure so the same color means the same thing wherever a
# reader sees it recur across different figures.
COLORS = [
    '#0072B2',
    '#D55E00',
    '#009E73',
    '#CC79A7',
    '#E69F00',
    '#56B4E9',
    '#F0E442',
]


def setup_plot_style():
    plt.rcParams['font.size'] = 10
    plt.rcParams['figure.dpi'] = 150
    plt.rcParams['savefig.dpi'] = 150
    plt.rcParams['axes.prop_cycle'] = plt.cycler(color=COLORS)


def load_csv(path):
    return pd.read_csv(path)
```

- [ ] **Step 3: Write the failing test**

`experiments/analysis/test_common.py`:

```python
"""Tests for common.py. Run: .venv/bin/python3 -m unittest test_common -v"""
import os
import unittest

import matplotlib.pyplot as plt

import common


class SetupPlotStyleTest(unittest.TestCase):
    def test_sets_the_fixed_color_cycle(self):
        common.setup_plot_style()
        cycle_colors = plt.rcParams['axes.prop_cycle'].by_key()['color']
        self.assertEqual(cycle_colors, common.COLORS)


class LoadCsvTest(unittest.TestCase):
    def test_loads_a_real_committed_csv(self):
        path = os.path.join(os.path.dirname(__file__), '..', 'results', 'e2', 'partition.csv')
        df = common.load_csv(path)
        self.assertEqual(len(df), 5)
        self.assertIn('online_probability', df.columns)
        self.assertIn('propagation_median_ticks', df.columns)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_common -v
```

Expected: both tests pass. (There is no "verify it fails first" step here, since
`common.py` and `test_common.py` are written together in this task rather than
test-first against not-yet-existing code; this matches how this project's other
sub-projects have handled a first, foundational task's own tests when the
brief's own code is written directly, not built up incrementally.)

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/requirements.txt experiments/analysis/common.py \
  experiments/analysis/test_common.py
git commit -m "feat(analysis): add the Python venv, common.py, and its tests"
```

(`experiments/analysis/.venv/` is not committed, since `.gitignore` already
excludes `.venv/` repository-wide.)

---

## Task 2: E1's crossover figure

**Files:**
- Create: `experiments/analysis/e1_figure.py`
- Test: `experiments/analysis/test_e1_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/e1-crossover.pdf` and `.png`; `e1_figure.aggregate(df) -> pandas.DataFrame`, `e1_figure.main() -> None`.

**Read first:** the real `experiments/results/e1/{baseline,structural,signature}.csv`
files directly. Confirmed at plan-writing time: 189 rows each (`batch_size` in
`{10, 50, 100, 500, 1000, 5000, 10000}`, `field_count` in `{1, 5, 20}`,
`grant_set_size` in `{1, 5, 11, 15, 20, 30, 51, 55, 70}`, `trial` in `{0, 1, 2}`,
7 x 3 x 3 x 3 = 189, where `grant_set_size` is itself derived from `field_count`
plus an independently-swept decoy count, giving 9 distinct realized values
rather than being its own independent 9-value sweep). Verify this still holds
against the files as they exist when you run this task, rather than trusting
this note if the files have changed since.

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_e1_figure.py`:

```python
"""Tests for e1_figure.py. Run: .venv/bin/python3 -m unittest test_e1_figure -v"""
import os
import unittest

import pandas as pd

import e1_figure


class AggregateTest(unittest.TestCase):
    def test_median_and_iqr_are_computed_per_batch_size_and_field_count(self):
        df = pd.DataFrame({
            'batch_size': [10, 10, 10, 50],
            'field_count': [1, 1, 1, 1],
            'grant_set_size': [1, 11, 51, 1],
            'trial': [0, 1, 2, 0],
            'wall_time_ms': [10.0, 20.0, 30.0, 100.0],
        })
        result = e1_figure.aggregate(df)
        row = result[(result['batch_size'] == 10) & (result['field_count'] == 1)].iloc[0]
        self.assertEqual(row['median'], 20.0)
        self.assertEqual(row['q25'], 15.0)
        self.assertEqual(row['q75'], 25.0)

    def test_pools_across_grant_set_size_and_trial_within_one_group(self):
        # Two different grant_set_size values (11 and 51) at the same
        # batch_size/field_count must be pooled into one median, not kept
        # separate: this is the "collapsed via aggregation" requirement from
        # spec section 4.1.
        df = pd.DataFrame({
            'batch_size': [10, 10],
            'field_count': [1, 1],
            'grant_set_size': [11, 51],
            'trial': [0, 0],
            'wall_time_ms': [10.0, 30.0],
        })
        result = e1_figure.aggregate(df)
        self.assertEqual(len(result), 1)
        self.assertEqual(result.iloc[0]['median'], 20.0)


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e1_figure.main()
        pdf_path = os.path.join(e1_figure.OUT_DIR, 'e1-crossover.pdf')
        png_path = os.path.join(e1_figure.OUT_DIR, 'e1-crossover.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e1_figure -v
```

Expected: FAIL, `e1_figure` module does not exist.

- [ ] **Step 3: Write `e1_figure.py`**

`experiments/analysis/e1_figure.py`:

```python
"""E1: consent filter cost, the crossover figure (spec section 4.1).

Reads the three raw per-trial CSVs (baseline/structural/signature), groups
by (batch_size, field_count) pooling across trial and grant_set_size, and
plots median wall_time_ms vs batch_size, one line per arm, three panels by
field_count. Uses real quantiles (not the median +/- iqr/2 approximation
this pipeline's already-aggregated CSVs need), since the raw per-trial data
is available here to compute them directly.
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_DIR = os.path.join(os.path.dirname(__file__), '..', 'results', 'e1')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')
ARMS = ['baseline', 'structural', 'signature']
FIELD_COUNTS = [1, 5, 20]


def load_arm(arm):
    df = load_csv(os.path.join(RESULTS_DIR, arm + '.csv'))
    df['arm'] = arm
    return df


def aggregate(df):
    grouped = df.groupby(['batch_size', 'field_count'])['wall_time_ms']
    median = grouped.median()
    q25 = grouped.quantile(0.25)
    q75 = grouped.quantile(0.75)
    result = median.to_frame('median').join(q25.to_frame('q25')).join(q75.to_frame('q75'))
    return result.reset_index()


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    frames = [load_arm(arm) for arm in ARMS]
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)

    for ax, field_count in zip(axes, FIELD_COUNTS):
        for frame in frames:
            arm = frame['arm'].iloc[0]
            subset = frame[frame['field_count'] == field_count]
            agg = aggregate(subset).sort_values('batch_size')
            ax.plot(agg['batch_size'], agg['median'], label=arm, marker='o')
            ax.fill_between(agg['batch_size'], agg['q25'], agg['q75'], alpha=0.2)
        ax.set_xscale('log')
        ax.set_yscale('log')
        ax.set_xlabel('batch size')
        ax.set_title('field_count = %d' % field_count)

    axes[0].set_ylabel('wall time (ms), median with IQR band')
    axes[0].legend()
    fig.suptitle('E1: consent filter cost by arm and batch size')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'e1-crossover.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'e1-crossover.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e1_figure -v
```

Expected: all 3 tests pass. Additionally, open `experiments/analysis/figures/e1-crossover.png`
(the Read tool can display images directly) and confirm it shows three panels,
each with three colored lines on log-log axes, roughly separating from each
other as batch size grows.

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/e1_figure.py experiments/analysis/test_e1_figure.py \
  experiments/analysis/figures/e1-crossover.pdf experiments/analysis/figures/e1-crossover.png
git commit -m "feat(analysis): add E1's crossover figure"
```

---

## Task 3: E2's propagation figure

**Files:**
- Create: `experiments/analysis/e2_figure.py`
- Test: `experiments/analysis/test_e2_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/e2-propagation.pdf` and `.png`; `e2_figure.main() -> None`.

**Read first:** `experiments/results/e2/partition.csv` directly. Confirmed at
plan-writing time: exactly 5 rows, one per swept `online_probability` value
(0.05, 0.1, 0.3, 0.5, 0.9), already aggregated (no grouping needed). This is
the simplest of the seven scripts.

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_e2_figure.py`:

```python
"""Tests for e2_figure.py. Run: .venv/bin/python3 -m unittest test_e2_figure -v"""
import os
import unittest

import e2_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e2_figure.main()
        pdf_path = os.path.join(e2_figure.OUT_DIR, 'e2-propagation.pdf')
        png_path = os.path.join(e2_figure.OUT_DIR, 'e2-propagation.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e2_figure -v
```

Expected: FAIL, `e2_figure` module does not exist.

- [ ] **Step 3: Write `e2_figure.py`**

`experiments/analysis/e2_figure.py`:

```python
"""E2: revocation propagation and consistency latency vs connectivity (spec
section 4.2). Already-aggregated data: IQR shading uses the standard
median +/- iqr/2 approximation, since only a single iqr column exists here
(not separate q25/q75 the way E1's raw per-trial data allows).
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'e2', 'partition.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH).sort_values('online_probability')

    fig, ax = plt.subplots(figsize=(6, 5))

    ax.plot(df['online_probability'], df['propagation_median_ticks'], label='propagation', marker='o')
    ax.fill_between(
        df['online_probability'],
        df['propagation_median_ticks'] - df['propagation_iqr_ticks'] / 2,
        df['propagation_median_ticks'] + df['propagation_iqr_ticks'] / 2,
        alpha=0.2,
    )
    ax.plot(df['online_probability'], df['consistency_median_ticks'], label='consistency', marker='o')
    ax.fill_between(
        df['online_probability'],
        df['consistency_median_ticks'] - df['consistency_iqr_ticks'] / 2,
        df['consistency_median_ticks'] + df['consistency_iqr_ticks'] / 2,
        alpha=0.2,
    )

    ax.set_xlabel('online probability (connectivity duty cycle)')
    ax.set_ylabel('ticks, median with IQR band')
    ax.set_title('E2: revocation propagation and consistency latency')
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'e2-propagation.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'e2-propagation.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e2_figure -v
```

Expected: the test passes. Open `experiments/analysis/figures/e2-propagation.png`
and confirm it shows two lines, both trending downward as online probability
increases, matching the real committed data (propagation median ticks
16.5/6/2/2/1 and consistency median ticks 12.5/9/2/2/1 across the five
swept points).

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/e2_figure.py experiments/analysis/test_e2_figure.py \
  experiments/analysis/figures/e2-propagation.pdf experiments/analysis/figures/e2-propagation.png
git commit -m "feat(analysis): add E2's propagation figure"
```

---

## Task 4: E3's verification-cost figure

**Files:**
- Create: `experiments/analysis/e3_figure.py`
- Test: `experiments/analysis/test_e3_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/e3-verification-cost.pdf` and `.png`; `e3_figure.aggregate_by_epoch_size(df, value_column) -> pandas.DataFrame`, `e3_figure.main() -> None`.

**Read first:** the real `experiments/results/e3/{proof-size,verify-inclusion-time,
verify-chain-time}.csv` files directly. Confirmed at plan-writing time:
`proof-size.csv` and `verify-inclusion-time.csv` each have 60 rows, `epoch_size`
in `{10, 50, 100, 500, 1000, 5000}` (6 values, 10 `leaf_index` rows per epoch
size), both needing the same group-by-`epoch_size` aggregation `e1_figure.py`'s
`aggregate` function already demonstrates the shape of (a separate, near-identical
function here, since it groups by one column instead of two and operates on a
caller-specified value column rather than a hardcoded one). `verify-chain-time.csv`
has exactly 4 rows, one per swept `chain_length` value (5, 20, 100, 500), already
one row per parameter value, needing no aggregation at all. Verify this still
holds against the files as they exist when you run this task.

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_e3_figure.py`:

```python
"""Tests for e3_figure.py. Run: .venv/bin/python3 -m unittest test_e3_figure -v"""
import os
import unittest

import pandas as pd

import e3_figure


class AggregateByEpochSizeTest(unittest.TestCase):
    def test_median_and_iqr_are_computed_per_epoch_size(self):
        df = pd.DataFrame({
            'epoch_size': [10, 10, 10, 50],
            'leaf_index': [0, 1, 2, 0],
            'elapsed_ms': [1.0, 2.0, 3.0, 10.0],
        })
        result = e3_figure.aggregate_by_epoch_size(df, 'elapsed_ms')
        row = result[result['epoch_size'] == 10].iloc[0]
        self.assertEqual(row['median'], 2.0)
        self.assertEqual(row['q25'], 1.5)
        self.assertEqual(row['q75'], 2.5)


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e3_figure.main()
        pdf_path = os.path.join(e3_figure.OUT_DIR, 'e3-verification-cost.pdf')
        png_path = os.path.join(e3_figure.OUT_DIR, 'e3-verification-cost.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e3_figure -v
```

Expected: FAIL, `e3_figure` module does not exist.

- [ ] **Step 3: Write `e3_figure.py`**

`experiments/analysis/e3_figure.py`:

```python
"""E3: audit tamper detection verification cost (spec section 4.3). Three
panels: proof size and inclusion-verification time (both aggregated across
leaf_index by epoch_size, using real quantiles from the raw per-leaf data),
and chain-of-custody verification time (already one row per chain_length,
no aggregation needed).
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_DIR = os.path.join(os.path.dirname(__file__), '..', 'results', 'e3')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')


def aggregate_by_epoch_size(df, value_column):
    grouped = df.groupby('epoch_size')[value_column]
    median = grouped.median()
    q25 = grouped.quantile(0.25)
    q75 = grouped.quantile(0.75)
    result = median.to_frame('median').join(q25.to_frame('q25')).join(q75.to_frame('q75'))
    return result.reset_index()


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    proof_size = aggregate_by_epoch_size(
        load_csv(os.path.join(RESULTS_DIR, 'proof-size.csv')), 'proof_bytes'
    ).sort_values('epoch_size')
    verify_inclusion = aggregate_by_epoch_size(
        load_csv(os.path.join(RESULTS_DIR, 'verify-inclusion-time.csv')), 'elapsed_ms'
    ).sort_values('epoch_size')
    verify_chain = load_csv(os.path.join(RESULTS_DIR, 'verify-chain-time.csv')).sort_values('chain_length')

    fig, (ax_size, ax_inclusion, ax_chain) = plt.subplots(1, 3, figsize=(16, 5))

    ax_size.plot(proof_size['epoch_size'], proof_size['median'], marker='o')
    ax_size.fill_between(proof_size['epoch_size'], proof_size['q25'], proof_size['q75'], alpha=0.2)
    ax_size.set_xscale('log')
    ax_size.set_xlabel('epoch size')
    ax_size.set_ylabel('proof size (bytes), median with IQR band')
    ax_size.set_title('inclusion proof size')

    ax_inclusion.plot(verify_inclusion['epoch_size'], verify_inclusion['median'], marker='o', color='#D55E00')
    ax_inclusion.fill_between(
        verify_inclusion['epoch_size'], verify_inclusion['q25'], verify_inclusion['q75'], alpha=0.2, color='#D55E00'
    )
    ax_inclusion.set_xscale('log')
    ax_inclusion.set_xlabel('epoch size')
    ax_inclusion.set_ylabel('verification time (ms), median with IQR band')
    ax_inclusion.set_title('inclusion proof verification time')

    ax_chain.plot(verify_chain['chain_length'], verify_chain['elapsed_ms'], marker='o', color='#009E73')
    ax_chain.set_xlabel('chain length')
    ax_chain.set_ylabel('verification time (ms)')
    ax_chain.set_title('chain-of-custody verification time')

    fig.suptitle('E3: audit tamper detection verification cost')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'e3-verification-cost.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'e3-verification-cost.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e3_figure -v
```

Expected: both tests pass. Open `experiments/analysis/figures/e3-verification-cost.png`
and confirm it shows three panels: proof size and inclusion-verify time both
rising with epoch size on log-x axes, and chain-verify time rising with
chain length on linear axes.

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/e3_figure.py experiments/analysis/test_e3_figure.py \
  experiments/analysis/figures/e3-verification-cost.pdf experiments/analysis/figures/e3-verification-cost.png
git commit -m "feat(analysis): add E3's verification-cost figure"
```

---

## Task 5: E4's utility figure

**Files:**
- Create: `experiments/analysis/e4_figure.py`
- Test: `experiments/analysis/test_e4_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/e4-utility.pdf` and `.png`; `e4_figure.main() -> None`.

**Read first:** `experiments/results/e4/utility.csv` directly. Confirmed at
plan-writing time: 256 rows total (128 after filtering to `skew == 'uniform'`),
`epsilon` in `{0.1, 0.27, 0.31, 0.37, 0.5, 1.0, 1.5, 2.0}` (8 values), `population`
in `{30, 100, 200, 300}` (4 values), `k` in `{3, 5, 7, 10}` (4 values). This is
the figure the parent spec calls "the article's most useful figure"; it must
use `surviving_median_relative_error`/`surviving_iqr_relative_error`, not the
plain `median_relative_error`/`iqr_relative_error` columns (see this plan's own
Global Constraints section for why).

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_e4_figure.py`:

```python
"""Tests for e4_figure.py. Run: .venv/bin/python3 -m unittest test_e4_figure -v"""
import os
import unittest

import e4_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e4_figure.main()
        pdf_path = os.path.join(e4_figure.OUT_DIR, 'e4-utility.pdf')
        png_path = os.path.join(e4_figure.OUT_DIR, 'e4-utility.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e4_figure -v
```

Expected: FAIL, `e4_figure` module does not exist.

- [ ] **Step 3: Write `e4_figure.py`**

`experiments/analysis/e4_figure.py`:

```python
"""E4: privacy budget versus utility, the paper's most useful figure (spec
section 4.4). Uses surviving_median_relative_error / surviving_iqr_relative_error
deliberately, not the plain median_relative_error / iqr_relative_error columns,
which are pinned at a sentinel value across most of the swept grid (this
session's own final-review fix wave added the surviving-only pair specifically
to make this figure show the real accuracy-versus-epsilon tradeoff).
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'e4', 'utility.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')
POPULATIONS = [30, 100, 200, 300]
K_VALUES = [3, 5, 7, 10]
IQR_BAND_K = 5


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH)
    df = df[df['skew'] == 'uniform']

    fig, axes = plt.subplots(1, 4, figsize=(20, 5), sharey=True)

    for ax, population in zip(axes, POPULATIONS):
        subset = df[df['population'] == population]
        for k in K_VALUES:
            k_subset = subset[subset['k'] == k].sort_values('epsilon')
            ax.plot(
                k_subset['epsilon'], k_subset['surviving_median_relative_error'],
                label='k=%d' % k, marker='o'
            )
            if k == IQR_BAND_K:
                ax.fill_between(
                    k_subset['epsilon'],
                    k_subset['surviving_median_relative_error'] - k_subset['surviving_iqr_relative_error'] / 2,
                    k_subset['surviving_median_relative_error'] + k_subset['surviving_iqr_relative_error'] / 2,
                    alpha=0.2,
                )
        ax.set_xscale('log')
        ax.set_xlabel('epsilon')
        ax.set_title('population = %d' % population)

    axes[0].set_ylabel('surviving median relative error')
    axes[0].legend()
    fig.suptitle('E4: privacy budget versus utility (uniform population)')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'e4-utility.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'e4-utility.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_e4_figure -v
```

Expected: the test passes. Open `experiments/analysis/figures/e4-utility.png`
and confirm it shows four panels, each with four colored lines trending
downward (improving accuracy) as epsilon increases, not a flat line at 1.0
(a flat line would indicate the wrong column was plotted; if you see one,
stop and check you are reading `surviving_median_relative_error`, not
`median_relative_error`).

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/e4_figure.py experiments/analysis/test_e4_figure.py \
  experiments/analysis/figures/e4-utility.pdf experiments/analysis/figures/e4-utility.png
git commit -m "feat(analysis): add E4's utility figure"
```

---

## Task 6: G1's availability figure

**Files:**
- Create: `experiments/analysis/g1_figure.py`
- Test: `experiments/analysis/test_g1_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/g1-availability.pdf` and `.png`; `g1_figure.main() -> None`.

**Read first:** `experiments/results/g1/availability.csv` directly. Confirmed
at plan-writing time: 135 rows total (45 after filtering to
`veto_share == 0.0`), `quorum_fraction` in `{0.3, 0.5, 0.7}` (3 values),
`p_act` in `{0.1, 0.3, 0.5, 0.7, 0.9}` (5 values).

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_g1_figure.py`:

```python
"""Tests for g1_figure.py. Run: .venv/bin/python3 -m unittest test_g1_figure -v"""
import os
import unittest

import g1_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        g1_figure.main()
        pdf_path = os.path.join(g1_figure.OUT_DIR, 'g1-availability.pdf')
        png_path = os.path.join(g1_figure.OUT_DIR, 'g1-availability.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_g1_figure -v
```

Expected: FAIL, `g1_figure` module does not exist.

- [ ] **Step 3: Write `g1_figure.py`**

`experiments/analysis/g1_figure.py`:

```python
"""G1: quorum availability and deadlock, real versus hypothetical
veto-blocking (spec section 4.5). Filters to veto_share == 0.0 to isolate
the real-versus-hypothetical comparison from the added complexity a nonzero
veto share would introduce into the same panels.
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'g1', 'availability.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')
QUORUM_FRACTIONS = [0.3, 0.5, 0.7]


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH)
    df = df[df['veto_share'] == 0.0]

    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)

    for ax, quorum_fraction in zip(axes, QUORUM_FRACTIONS):
        subset = df[df['quorum_fraction'] == quorum_fraction].sort_values('p_act')
        ax.plot(
            subset['p_act'], subset['arm_a_starved_rate'],
            label='arm A (real)', marker='o', linestyle='-', color='#0072B2'
        )
        ax.plot(
            subset['p_act'], subset['arm_b_starved_rate'],
            label='arm B (hypothetical)', marker='o', linestyle='--', color='#0072B2'
        )
        ax.set_xlabel('p_act')
        ax.set_ylim(-0.05, 1.05)
        ax.set_title('quorum_fraction = %.1f' % quorum_fraction)

    axes[0].set_ylabel('starved rate')
    axes[0].legend()
    fig.suptitle('G1: quorum availability, real versus hypothetical veto-blocking')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'g1-availability.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'g1-availability.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_g1_figure -v
```

Expected: the test passes. Open `experiments/analysis/figures/g1-availability.png`
and confirm it shows three panels, each with a solid and a dashed line, the
dashed (hypothetical) line at or above the solid (real) line's starved rate
throughout (a hypothetical policy that also blocks on vetoes can only starve
at least as often as one that does not).

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/g1_figure.py experiments/analysis/test_g1_figure.py \
  experiments/analysis/figures/g1-availability.pdf experiments/analysis/figures/g1-availability.png
git commit -m "feat(analysis): add G1's availability figure"
```

---

## Task 7: G2's heterogeneity figure

**Files:**
- Create: `experiments/analysis/g2_figure.py`
- Test: `experiments/analysis/test_g2_figure.py`

**Interfaces:**
- Consumes: `common.setup_plot_style`, `common.load_csv` (Task 1).
- Produces: `experiments/analysis/figures/g2-heterogeneity.pdf` and `.png`; `g2_figure.main() -> None`.

**Read first:** `experiments/results/g2/heterogeneity.csv` directly. Confirmed
at plan-writing time: 56 rows total (28 after filtering to `population == 100`),
`epsilon` in `{0.25, 0.5, 1.0, 2.0}` (4 values), `h` in
`{0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0}` (7 values). Uses the same
`surviving_*` columns as E4, for the identical reason (see this plan's own
Global Constraints section).

- [ ] **Step 1: Write the failing test**

`experiments/analysis/test_g2_figure.py`:

```python
"""Tests for g2_figure.py. Run: .venv/bin/python3 -m unittest test_g2_figure -v"""
import os
import unittest

import g2_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        g2_figure.main()
        pdf_path = os.path.join(g2_figure.OUT_DIR, 'g2-heterogeneity.pdf')
        png_path = os.path.join(g2_figure.OUT_DIR, 'g2-heterogeneity.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_g2_figure -v
```

Expected: FAIL, `g2_figure` module does not exist.

- [ ] **Step 3: Write `g2_figure.py`**

`experiments/analysis/g2_figure.py`:

```python
"""G2: consent heterogeneity versus aggregate utility (spec section 4.6).
Uses surviving_coverage_median_relative_error / surviving_exclusion_median_relative_error
(and their iqr counterparts), the same surviving-only pattern E4 uses and for
the identical reason: the plain columns are pinned at a sentinel value across
most of the swept grid.
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'g2', 'heterogeneity.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')
EPSILONS = [0.25, 0.5, 1.0, 2.0]


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH)
    df = df[df['population'] == 100]

    fig, axes = plt.subplots(1, 4, figsize=(20, 5), sharey=True)

    for ax, epsilon in zip(axes, EPSILONS):
        subset = df[df['epsilon'] == epsilon].sort_values('h')
        ax.plot(subset['h'], subset['surviving_coverage_median_relative_error'], label='coverage', marker='o')
        ax.fill_between(
            subset['h'],
            subset['surviving_coverage_median_relative_error'] - subset['surviving_coverage_iqr_relative_error'] / 2,
            subset['surviving_coverage_median_relative_error'] + subset['surviving_coverage_iqr_relative_error'] / 2,
            alpha=0.2,
        )
        ax.plot(subset['h'], subset['surviving_exclusion_median_relative_error'], label='exclusion', marker='o')
        ax.fill_between(
            subset['h'],
            subset['surviving_exclusion_median_relative_error'] - subset['surviving_exclusion_iqr_relative_error'] / 2,
            subset['surviving_exclusion_median_relative_error'] + subset['surviving_exclusion_iqr_relative_error'] / 2,
            alpha=0.2,
        )
        ax.set_xlabel('h (heterogeneity)')
        ax.set_title('epsilon = %.2f' % epsilon)

    axes[0].set_ylabel('surviving median relative error')
    axes[0].legend()
    fig.suptitle('G2: consent heterogeneity versus aggregate utility (population = 100)')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'g2-heterogeneity.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'g2-heterogeneity.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest test_g2_figure -v
```

Expected: the test passes. Open `experiments/analysis/figures/g2-heterogeneity.png`
and confirm it shows four panels, each with two lines: coverage roughly flat,
exclusion rising as `h` increases (the sub-project's own headline finding).

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/g2_figure.py experiments/analysis/test_g2_figure.py \
  experiments/analysis/figures/g2-heterogeneity.pdf experiments/analysis/figures/g2-heterogeneity.png
git commit -m "feat(analysis): add G2's heterogeneity figure"
```

---

## Task 8: `generate_all.py` and final verification

**Files:**
- Create: `experiments/analysis/generate_all.py`

**Interfaces:**
- Consumes: `e1_figure.main`, `e2_figure.main`, `e3_figure.main`, `e4_figure.main`, `g1_figure.main`, `g2_figure.main` (Tasks 2-7, all already committed).
- Produces: a single command that regenerates every figure from the current committed CSVs.

- [ ] **Step 1: Write `generate_all.py`**

`experiments/analysis/generate_all.py`:

```python
"""Runs every phase-7 figure script in sequence, from the current committed
CSVs, so a figure can never drift from the numbers behind it (spec section 5).
"""
import sys

import e1_figure
import e2_figure
import e3_figure
import e4_figure
import g1_figure
import g2_figure

SCRIPTS = [
    ('E1', e1_figure),
    ('E2', e2_figure),
    ('E3', e3_figure),
    ('E4', e4_figure),
    ('G1', g1_figure),
    ('G2', g2_figure),
]


def main():
    for name, module in SCRIPTS:
        print('generating %s figure' % name)
        try:
            module.main()
        except Exception as exc:
            print('failed generating %s figure: %s' % (name, exc))
            sys.exit(1)
    print('all figures generated')


if __name__ == '__main__':
    main()
```

- [ ] **Step 2: Run it and verify every figure regenerates**

```bash
cd experiments/analysis
.venv/bin/python3 generate_all.py
```

Expected output: six "generating <NAME> figure" lines followed by
"all figures generated", exit code 0. Confirm all 14 files
(`experiments/analysis/figures/{e1-crossover,e2-propagation,e3-verification-cost,
e4-utility,g1-availability,g2-heterogeneity}.{pdf,png}`) exist and were just
rewritten (check modification times, or delete the `figures/` directory
entirely before this step and confirm it is fully repopulated).

- [ ] **Step 3: Run the full test suite one more time**

```bash
cd experiments/analysis
.venv/bin/python3 -m unittest discover -p 'test_*.py' -v
```

Expected: every test across all seven test files passes (Task 1's
`test_common.py` plus one test file per experiment).

- [ ] **Step 4: Visually inspect every PNG**

Open each of the six PNG files under `experiments/analysis/figures/` (the
Read tool displays images directly) one more time, together, and confirm
each one matches its own task's own description above: E1 shows a clear
crossover pattern across three arms; E2 shows two downward-trending lines;
E3 shows size and time both rising with epoch size, plus a third small
chain-length panel; E4 shows four panels of downward-trending accuracy
curves, not flat lines; G1 shows solid/dashed line pairs with the dashed
line never below the solid one; G2 shows a flat coverage line and a rising
exclusion line in every panel.

- [ ] **Step 5: Commit**

```bash
git add experiments/analysis/generate_all.py
git commit -m "feat(analysis): add generate_all.py, the full figure-generation entry point"
```

---

## Final verification (part of the last task's own checklist, not a separate task)

Confirm nothing outside `experiments/analysis/` was touched by this whole
plan: `git diff --stat <plan-start-commit> HEAD -- . ':!experiments/analysis'`
should be empty, since this sub-project only reads already-committed CSVs
and writes new files under one new directory.
