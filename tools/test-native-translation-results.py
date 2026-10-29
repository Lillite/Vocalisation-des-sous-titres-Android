#!/usr/bin/env python3
"""Regression checks for real native log evidence; no model/phone performance claim."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('native_translation_runner', Path(__file__).with_name('run-native-translation-tests.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def evidence(error, delta):
    return f'Context-sensitive logits: cached-vs-fresh maximum error {error}, changed-context delta {delta}.\n'


class NativeTranslationResultsTest(unittest.TestCase):
    def test_actual_sentence_punctuation_is_not_part_of_the_number(self):
        text = 'Other native measurements.\n' + evidence('0', '0.000209779') + 'All assertions passed.\n'
        self.assertEqual((0.0, 0.000209779), runner.parse_logit_comparison(text))

    def test_finite_scientific_notation_and_signed_zero_are_supported(self):
        self.assertEqual((-0.0, 0.000209779), runner.parse_logit_comparison(evidence('-0', '2.09779e-4')))
        self.assertEqual((0.000001, 0.0002), runner.parse_logit_comparison(evidence('1e-6', '+.0002')))

    def test_invalid_missing_and_ambiguous_evidence_is_rejected(self):
        valid = evidence('0', '0.000209779')
        for value in ['', 'All assertions passed.', valid + valid, valid.replace('779.', '779..'),
                      evidence('NaN', '0.1'), evidence('0', 'inf'), evidence('0', '0.000209779')[:-2]]:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                runner.parse_logit_comparison(value)

    def test_boundaries_nonfinite_values_and_loss_of_context_sensitivity_are_rejected(self):
        for error, delta in [('0.00002', '0.1'), ('-0.000001', '0.1'), ('0', '0.000001'),
                             ('0', '0'), ('0', '-0.1'), ('0', '1e999'), ('1e999', '0.1')]:
            with self.subTest(error=error, delta=delta), self.assertRaises(RuntimeError):
                runner.parse_logit_comparison(evidence(error, delta))


if __name__ == '__main__':
    unittest.main()
