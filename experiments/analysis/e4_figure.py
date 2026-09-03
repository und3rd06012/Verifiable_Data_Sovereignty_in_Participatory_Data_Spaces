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
            line, = ax.plot(
                k_subset['epsilon'], k_subset['surviving_median_relative_error'],
                label='k=%d' % k, marker='o'
            )
            if k == IQR_BAND_K:
                ax.fill_between(
                    k_subset['epsilon'],
                    k_subset['surviving_median_relative_error'] - k_subset['surviving_iqr_relative_error'] / 2,
                    k_subset['surviving_median_relative_error'] + k_subset['surviving_iqr_relative_error'] / 2,
                    alpha=0.2,
                    color=line.get_color(),
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
