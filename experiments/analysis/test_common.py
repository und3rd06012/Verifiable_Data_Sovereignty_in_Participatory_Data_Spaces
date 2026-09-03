"""Tests for common.py. Run: .venv/bin/python3 -m unittest test_common -v"""
import os
import unittest

import matplotlib.pyplot as plt

import common


class SetupPlotStyleTest(unittest.TestCase):
    def test_sets_the_fixed_color_cycle(self):
        common.setup_plot_style()
        cycle_colors = plt.rcParams['axes.prop_cycle'].by_key()['color']
        self.assertEqual(cycle_colors, common.COLORS)


class LoadCsvTest(unittest.TestCase):
    def test_loads_a_real_committed_csv(self):
        path = os.path.join(os.path.dirname(__file__), '..', 'results', 'e2', 'partition.csv')
        df = common.load_csv(path)
        self.assertEqual(len(df), 5)
        self.assertIn('online_probability', df.columns)
        self.assertIn('propagation_median_ticks', df.columns)


if __name__ == '__main__':
    unittest.main()
