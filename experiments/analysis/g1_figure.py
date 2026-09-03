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
