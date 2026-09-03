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
