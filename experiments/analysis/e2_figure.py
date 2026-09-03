"""E2: revocation propagation and consistency latency vs connectivity (spec
section 4.2). Already-aggregated data: IQR shading uses the standard
median +/- iqr/2 approximation, since only a single iqr column exists here
(not separate q25/q75 the way E1's raw per-trial data allows).
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv, MARKERS

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'e2', 'partition.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH).sort_values('online_probability')

    fig, ax = plt.subplots(figsize=(6, 5))

    prop_line, = ax.plot(df['online_probability'], df['propagation_median_ticks'], label='propagation', marker=MARKERS[0])
    ax.fill_between(
        df['online_probability'],
        df['propagation_median_ticks'] - df['propagation_iqr_ticks'] / 2,
        df['propagation_median_ticks'] + df['propagation_iqr_ticks'] / 2,
        alpha=0.2, color=prop_line.get_color(),
    )
    cons_line, = ax.plot(df['online_probability'], df['consistency_median_ticks'], label='consistency', marker=MARKERS[1])
    ax.fill_between(
        df['online_probability'],
        df['consistency_median_ticks'] - df['consistency_iqr_ticks'] / 2,
        df['consistency_median_ticks'] + df['consistency_iqr_ticks'] / 2,
        alpha=0.2, color=cons_line.get_color(),
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
