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

from common import setup_plot_style, load_csv, MARKERS

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
        for frame, marker in zip(frames, MARKERS):
            arm = frame['arm'].iloc[0]
            subset = frame[frame['field_count'] == field_count]
            agg = aggregate(subset).sort_values('batch_size')
            line, = ax.plot(agg['batch_size'], agg['median'], label=arm, marker=marker)
            ax.fill_between(agg['batch_size'], agg['q25'], agg['q75'], alpha=0.2, color=line.get_color())
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
