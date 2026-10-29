import unittest
from unittest import mock

import memory_evidence as evidence

import long_memory_diag as long
import memory_diag as md


class LongMemoryTest(unittest.TestCase):
    def test_interval_normalizes_unequal_intervals(self):
        self.assertEqual(long.interval([10, 40, 120], [0, 30, 100]), [100, 8000 / 70])

    def test_comparison_matches_repetition_and_rejects_missing_control(self):
        self.assertEqual(long.paired_difference({2: 12, 1: 10}, {1: 4, 2: 7}), [6, 5])
        with self.assertRaises(ValueError):
            long.paired_difference({1: 1}, {2: 2})

    def test_invalid_interval_is_rejected(self):
        with self.assertRaises(ValueError):
            long.interval([1, 2], [30, 30])

    def test_long_inventory_rejects_missing_marker_and_wrong_plan(self):
        text = ('profile=long\nplan=structural:10000:3:off,on:plain,nmt:150:0\n'
                'plan=install:1000:3:on:plain,nmt:0:1000\n')
        cells = {('install', 1000, 'on', 'plain'): [('install-n1000-on-plain-r1', {})]}
        with mock.patch.object(evidence, 'environment', return_value=text), \
             mock.patch.object(evidence.md, 'load', return_value=cells):
            with self.assertRaises(ValueError):
                evidence.verify(None, {'version': 1, 'artifacts': {}}, 'full')
        with mock.patch.object(evidence, 'environment', return_value=text.replace('150:0', '100:0')), \
             mock.patch.object(evidence.md, 'load', return_value=cells):
            with self.assertRaises(ValueError):
                evidence.verify(None, {'version': 1, 'artifacts': {}}, 'full')

    def test_long_snapshots_are_in_inventory_and_require_histogram(self):
        run = {'structuralCycle30SmapsRssKb': 1}
        artifacts = md.expected_artifacts(run, 'nmt')
        self.assertIn(('structuralCycle30', 'histo'), artifacts)
        self.assertIn(('structuralCycle30', 'nmt'), artifacts)
        self.assertIn('structuralCycle30', md.CHECKPOINT_ORDER)
