"""G1: quorum availability and deadlock, real versus hypothetical
veto-blocking (spec section 4.5). Filters to veto_share == 0.3, the
largest of the three swept values ({0.0, 0.1, 0.3}): at veto_share == 0.0
no vetoes ever occur, so Arm B degenerates to exactly Arm A's behavior by
construction and the figure would show two flat, identical, zero-valued
lines with no comparison at all, whereas veto_share == 0.3 shows the
largest divergence between the arms.

Also filters to N == 20, the largest of the three swept steward pool
sizes ({5, 10, 20}, matching this sub-project's own spec calling it "the
largest pool" and the parent spec's "community scale" framing). Each
panel's x-axis (p_act) and legend (arm) do not encode N, so without this
filter the three swept N values' rows would land at the same p_act
x-position and get connected by line segments in sort order, producing a
visible zigzag rather than one clean line per arm per panel. N == 20
alone gives exactly one row per (quorum_fraction, p_act) pair, avoiding
the issue entirely.

Plots 1 - reached_rate (quorum failure rate) rather than the raw
starved_rate columns directly, because Arm B's failures split across two
mutually exclusive buckets, a permanent VETOED bucket and a STARVED
bucket, and arm_b_starved_rate alone excludes its dominant failure mode.
"""
import os

import matplotlib.pyplot as plt

from common import setup_plot_style, load_csv, MARKERS

RESULTS_PATH = os.path.join(os.path.dirname(__file__), '..', 'results', 'g1', 'availability.csv')
OUT_DIR = os.path.join(os.path.dirname(__file__), 'figures')
QUORUM_FRACTIONS = [0.3, 0.5, 0.7]


def main():
    setup_plot_style()
    os.makedirs(OUT_DIR, exist_ok=True)

    df = load_csv(RESULTS_PATH)
    df = df[(df['veto_share'] == 0.3) & (df['N'] == 20)]

    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)

    for ax, quorum_fraction in zip(axes, QUORUM_FRACTIONS):
        subset = df[df['quorum_fraction'] == quorum_fraction].sort_values('p_act')
        ax.plot(
            subset['p_act'], 1 - subset['arm_a_reached_rate'],
            label='arm A (real)', marker=MARKERS[0], linestyle='-', color='#0072B2'
        )
        ax.plot(
            subset['p_act'], subset['arm_b_starved_rate'] + subset['arm_b_vetoed_rate'],
            label='arm B (hypothetical)', marker=MARKERS[1], linestyle='--', color='#0072B2'
        )
        ax.set_xlabel('p_act')
        ax.set_ylim(-0.05, 1.05)
        ax.set_title('quorum_fraction = %.1f' % quorum_fraction)

    axes[0].set_ylabel('quorum failure rate')
    axes[0].legend()
    fig.suptitle('G1: quorum availability, real versus hypothetical veto-blocking')
    fig.tight_layout()
    fig.savefig(os.path.join(OUT_DIR, 'g1-availability.pdf'))
    fig.savefig(os.path.join(OUT_DIR, 'g1-availability.png'))
    plt.close(fig)


if __name__ == '__main__':
    main()
