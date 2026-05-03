"""Self-test for analyze.py's classifier — synthesises known frame types and asserts
the classifier labels them correctly."""

import json
import os
import sys
import tempfile
import unittest

import numpy as np

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)
from analyze import classify_frame, FrameRecord  # noqa: E402


class ClassifierTest(unittest.TestCase):

    def _record(self, mode, hist, y_min, y_max, y_mean, dataspace_name="DATASPACE_BT2020_PQ"):
        return FrameRecord(
            mode=mode, index=0,
            dataspace_int=0, dataspace_name=dataspace_name,
            y_min=y_min, y_max=y_max, y_mean=y_mean,
            uv_u_mean=512, uv_v_mean=512,
            y_histogram_32bin=list(hist),
        )

    def test_pq_shaped_when_full_range_used_with_bright_highlights(self):
        # Y values broadly distributed with substantial weight in upper bins (>=24)
        hist = np.zeros(32, dtype=int)
        hist[2:30] = 100
        hist[24:30] = 400  # bright HDR highlights
        rec = self._record("hdr", hist, y_min=64, y_max=940, y_mean=480)
        self.assertEqual(classify_frame(rec), "PQ-shaped")

    def test_sdr_stretched_when_compressed_to_low_bins(self):
        # All values bunched in 0..15 (limited-range SDR mapped to 10-bit but with no
        # bright highlights extending past mid)
        hist = np.zeros(32, dtype=int)
        hist[2:8] = 1000
        rec = self._record("hdr", hist, y_min=64, y_max=235, y_mean=120)
        self.assertEqual(classify_frame(rec), "SDR-stretched")

    def test_zero_or_broken_when_almost_all_in_first_bin(self):
        hist = np.zeros(32, dtype=int)
        hist[0] = 999_999
        hist[1] = 1
        rec = self._record("hdr", hist, y_min=0, y_max=1, y_mean=0)
        self.assertEqual(classify_frame(rec), "zero-or-broken")

    def test_dataspace_override_marks_no_go_regardless_of_histogram(self):
        # Even if the histogram looks PQ, if dataspace came back as SRGB the device overrode us.
        hist = np.zeros(32, dtype=int)
        hist[2:30] = 100; hist[24:30] = 400
        rec = self._record("hdr", hist, y_min=64, y_max=940, y_mean=480,
                           dataspace_name="DATASPACE_SRGB")
        self.assertEqual(classify_frame(rec), "dataspace-overridden")


if __name__ == "__main__":
    unittest.main()
