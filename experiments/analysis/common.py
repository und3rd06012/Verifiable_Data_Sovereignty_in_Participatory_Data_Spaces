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

# Paired with COLORS by position so a series is distinguishable by shape as
# well as color; used explicitly by each figure script's own plot() calls
# (marker shape isn't an rcParam matplotlib cycles automatically).
MARKERS = ['o', 's', '^', 'D', 'v', 'P']


def setup_plot_style():
    plt.rcParams['font.family'] = 'sans-serif'
    plt.rcParams['font.sans-serif'] = ['Inter', 'DejaVu Sans']
    plt.rcParams['font.size'] = 10
    plt.rcParams['figure.dpi'] = 150
    plt.rcParams['savefig.dpi'] = 150
    plt.rcParams['axes.prop_cycle'] = plt.cycler(color=COLORS)
    # A restrained, conventional scientific-journal look: thin lines, hollow
    # markers with a colored outline, open top/right spines, light y-only
    # gridlines, muted axis chrome, frameless legend.
    plt.rcParams['lines.linewidth'] = 1.5
    plt.rcParams['lines.markersize'] = 5.5
    plt.rcParams['lines.markerfacecolor'] = 'white'
    plt.rcParams['lines.markeredgewidth'] = 1.4
    plt.rcParams['axes.edgecolor'] = '#374151'
    plt.rcParams['axes.linewidth'] = 1.0
    plt.rcParams['axes.spines.top'] = False
    plt.rcParams['axes.spines.right'] = False
    plt.rcParams['axes.grid'] = True
    plt.rcParams['axes.grid.axis'] = 'y'
    plt.rcParams['grid.color'] = '#e5e7eb'
    plt.rcParams['grid.linewidth'] = 0.8
    plt.rcParams['xtick.color'] = '#374151'
    plt.rcParams['ytick.color'] = '#374151'
    plt.rcParams['axes.labelcolor'] = '#374151'
    plt.rcParams['legend.frameon'] = False
    plt.rcParams['legend.fontsize'] = 9


def load_csv(path):
    return pd.read_csv(path)
