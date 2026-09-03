"""Tests for e4_figure.py. Run: .venv/bin/python3 -m unittest test_e4_figure -v"""
import os
import unittest

import e4_figure


class MainTest(unittest.TestCase):
    def test_produces_pdf_and_png_output_from_the_real_committed_data(self):
        e4_figure.main()
        pdf_path = os.path.join(e4_figure.OUT_DIR, 'e4-utility.pdf')
        png_path = os.path.join(e4_figure.OUT_DIR, 'e4-utility.png')
        self.assertTrue(os.path.exists(pdf_path))
        self.assertTrue(os.path.exists(png_path))
        self.assertGreater(os.path.getsize(pdf_path), 0)
        self.assertGreater(os.path.getsize(png_path), 0)


if __name__ == '__main__':
    unittest.main()
