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
