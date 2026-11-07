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


def chat_evidence(fixtures=14, families=3, literal=3, generations=55, rejected=18, boundaries=5):
    return (f'GGUF builtin chat formats: fixture_models={fixtures}, exact_prompt_families={families}, '
            f'literal_user_checks={literal}, successful_generations={generations}, '
            f'rejected_requests={rejected}, boundary_checks={boundaries}.\n')


class NativeTranslationResultsTest(unittest.TestCase):
    def test_complete_optimization_evidence_keeps_quantized_deltas_separate(self):
        valid = ('Moving two-turn window: requests=25, post-warmup workspace growths=0, workspace bytes=4352, '
                 'cache resets=25, cached-vs-fresh maximum error 0, elapsed including load us=12345.\n'
                 'Quantized cache Llama: requests=25, cached-vs-fresh maximum error 0, Q8-vs-F16 maximum error 0.001, synthetic output tokens equal=1.\n'
                 'Quantized cache Gemma-SWA: requests=25, cached-vs-fresh maximum error 1e-6, Q8-vs-F16 maximum error 0.002, synthetic output tokens equal=1.\n')
        result = runner.parse_optimization_evidence(valid)
        self.assertEqual(0, result['movingWindow']['workspaceGrowthsAfterWarmup'])
        self.assertEqual(0.002, result['quantizedCache']['Gemma-SWA']['q8VsF16MaxLogitError'])
        self.assertFalse(result['phoneSpeedupMeasured'])
        self.assertFalse(result['linguisticEquivalenceEstablished'])
        for invalid in ['', valid + valid, valid.replace('requests=25', 'requests=24', 1),
                        valid.replace('growths=0', 'growths=1'), valid.replace('bytes=4352', 'bytes=1024'),
                        valid.replace('resets=25', 'resets=0'), valid.replace('resets=25', 'resets=1'), valid.replace('error 0,', 'error 0.00002,', 1),
                        valid.replace('error 0.001,', 'error 1e999,'),
                        valid.replace('Gemma-SWA', 'Llama'), valid.replace('equal=1', 'equal=0')]:
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                runner.parse_optimization_evidence(invalid)

    def test_compact_swa_requires_wrap_recompute_short_reuse_and_bounded_finite_errors(self):
        valid = ('Compact SWA: requests=6, safe_recompute=1, short_reuse=1, '
                 'cached-vs-fresh maximum error 0, compact-vs-full maximum error 2.69338e-05, '
                 'compact-vs-full relative error 1.1e-06.\n')
        result = runner.parse_swa_comparison(valid)
        self.assertTrue(result['safeRecompute'] and result['shortPrefixReuse'])
        self.assertEqual(2.69338e-05, result['compactVsFullMaxLogitError'])
        for invalid in ['', valid + valid, valid.replace('requests=6', 'requests=5'),
                        valid.replace('safe_recompute=1', 'safe_recompute=0'),
                        valid.replace('short_reuse=1', 'short_reuse=0'),
                        valid.replace('error 0,', 'error 0.00002,'),
                        valid.replace('2.69338e-05', '0.0001'),
                        valid.replace('1.1e-06', '0.00001'),
                        valid.replace('1.1e-06', 'NaN'), valid.replace('2.69338e-05', '1e999')]:
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                runner.parse_swa_comparison(invalid)

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

    def test_complete_real_builtin_chat_evidence_is_required(self):
        log = evidence('0', '0.0002') + chat_evidence() + 'Other completed native checks.\n'
        self.assertEqual(runner.CHAT_FORMAT_COUNTS, runner.parse_chat_format_evidence(log))

    def test_missing_duplicate_or_malformed_chat_evidence_is_rejected(self):
        valid = chat_evidence()
        for log in ['', evidence('0', '0.0002'), valid + valid, valid[:-2],
                    valid.replace('=14,', '=14.0,'), valid.replace('5.', '5..'),
                    valid.replace('builtin chat formats', 'chat templates')]:
            with self.subTest(log=log), self.assertRaises(RuntimeError):
                runner.parse_chat_format_evidence(log)

    def test_partial_chat_suite_cannot_be_reported_as_passed(self):
        for counts in [dict(fixtures=13), dict(families=2), dict(literal=2),
                       dict(generations=54), dict(rejected=17), dict(boundaries=4)]:
            with self.subTest(counts=counts), self.assertRaises(RuntimeError):
                runner.parse_chat_format_evidence(chat_evidence(**counts))

    def test_changed_unreviewed_chat_counts_cannot_silently_change_the_evidence(self):
        for counts in [dict(fixtures=15), dict(generations=56), dict(rejected=-1), dict(boundaries=6)]:
            with self.subTest(counts=counts), self.assertRaises(RuntimeError):
                runner.parse_chat_format_evidence(chat_evidence(**counts))


if __name__ == '__main__':
    unittest.main()
