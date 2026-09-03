"""Tests for g1_figure.py. Run: .venv/bin/python3 -m unittest test_g1_figure -v"""
import os
import unittest

import g1_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        g1_figure.main()
        pdf_path = os.path.join(g1_figure.OUT_DIR, 'g1-availability.pdf')
        png_path = os.path.join(g1_figure.OUT_DIR, 'g1-availability.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
