"""G2: consent heterogeneity versus aggregate utility (spec section 4.6).
Uses surviving_coverage_median_relative_error / surviving_exclusion_median_relative_error
(and their iqr counterparts), the same surviving-only pattern E4 uses and for
the identical reason: the plain columns are pinned at a sentinel value across
most of the swept grid.
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv, MARKERS

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
        coverage_line, = ax.plot(subset['h'], subset['surviving_coverage_median_relative_error'], label='coverage', marker=MARKERS[0])
        ax.fill_between(
            subset['h'],
            subset['surviving_coverage_median_relative_error'] - subset['surviving_coverage_iqr_relative_error'] / 2,
            subset['surviving_coverage_median_relative_error'] + subset['surviving_coverage_iqr_relative_error'] / 2,
            alpha=0.2,
            color=coverage_line.get_color(),
        )
        exclusion_line, = ax.plot(subset['h'], subset['surviving_exclusion_median_relative_error'], label='exclusion', marker=MARKERS[1])
        ax.fill_between(
            subset['h'],
            subset['surviving_exclusion_median_relative_error'] - subset['surviving_exclusion_iqr_relative_error'] / 2,
            subset['surviving_exclusion_median_relative_error'] + subset['surviving_exclusion_iqr_relative_error'] / 2,
            alpha=0.2,
            color=exclusion_line.get_color(),
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
