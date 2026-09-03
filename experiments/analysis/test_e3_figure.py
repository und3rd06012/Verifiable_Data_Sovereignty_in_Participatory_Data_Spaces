"""Tests for e3_figure.py. Run: .venv/bin/python3 -m unittest test_e3_figure -v"""
import os
import unittest

import pandas as pd

import e3_figure


class AggregateByEpochSizeTest(unittest.TestCase):
    def test_median_and_iqr_are_computed_per_epoch_size(self):
        df = pd.DataFrame({
            'epoch_size': [10, 10, 10, 50],
            'leaf_index': [0, 1, 2, 0],
            'elapsed_ms': [1.0, 2.0, 3.0, 10.0],
        })
        result = e3_figure.aggregate_by_epoch_size(df, 'elapsed_ms')
        row = result[result['epoch_size'] == 10].iloc[0]
        self.assertEqual(row['median'], 2.0)
        self.assertEqual(row['q25'], 1.5)
        self.assertEqual(row['q75'], 2.5)


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e3_figure.main()
        pdf_path = os.path.join(e3_figure.OUT_DIR, 'e3-verification-cost.pdf')
        png_path = os.path.join(e3_figure.OUT_DIR, 'e3-verification-cost.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
