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
