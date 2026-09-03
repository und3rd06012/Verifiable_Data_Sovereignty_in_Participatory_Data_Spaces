"""Tests for e1_figure.py. Run: .venv/bin/python3 -m unittest test_e1_figure -v"""
import os
import unittest

import pandas as pd

import e1_figure


class AggregateTest(unittest.TestCase):
    def test_median_and_iqr_are_computed_per_batch_size_and_field_count(self):
        df = pd.DataFrame({
            'batch_size': [10, 10, 10, 50],
            'field_count': [1, 1, 1, 1],
            'grant_set_size': [1, 11, 51, 1],
            'trial': [0, 1, 2, 0],
            'wall_time_ms': [10.0, 20.0, 30.0, 100.0],
        })
        result = e1_figure.aggregate(df)
        row = result[(result['batch_size'] == 10) & (result['field_count'] == 1)].iloc[0]
        self.assertEqual(row['median'], 20.0)
        self.assertEqual(row['q25'], 15.0)
        self.assertEqual(row['q75'], 25.0)

    def test_pools_across_grant_set_size_and_trial_within_one_group(self):
        # Two different grant_set_size values (11 and 51) at the same
        # batch_size/field_count must be pooled into one median, not kept
        # separate: this is the "collapsed via aggregation" requirement from
        # spec section 4.1.
        df = pd.DataFrame({
            'batch_size': [10, 10],
            'field_count': [1, 1],
            'grant_set_size': [11, 51],
            'trial': [0, 0],
            'wall_time_ms': [10.0, 30.0],
        })
        result = e1_figure.aggregate(df)
        self.assertEqual(len(result), 1)
        self.assertEqual(result.iloc[0]['median'], 20.0)


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e1_figure.main()
        pdf_path = os.path.join(e1_figure.OUT_DIR, 'e1-crossover.pdf')
        png_path = os.path.join(e1_figure.OUT_DIR, 'e1-crossover.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
