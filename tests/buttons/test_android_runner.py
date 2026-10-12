"""Host-only instrumentation-output parser regressions; no device commands."""
import unittest
from run_android_checks import instrumentation_result


def record(method, code):
    return (f'INSTRUMENTATION_STATUS: test={method}\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n')


class InstrumentationResultTest(unittest.TestCase):
    def test_complete_success(self):
        out = record('testOne', 1) + record('testOne', 0) + 'OK (1 test)\n'
        result = instrumentation_result(out, ['testOne'])
        self.assertEqual('PASS', result['result'])
        self.assertEqual(1, result['passed_tests'])
        self.assertEqual([], result['failed_methods'])

    def test_partial_failure_keeps_actual_pass_count(self):
        out = (record('testOne', 1) + record('testOne', 0)
               + record('testTwo', 1) + record('testTwo', -2)
               + 'FAILURES!!!\nTests run: 2,  Failures: 1,  Errors: 0\n')
        result = instrumentation_result(out, ['testOne', 'testTwo'])
        self.assertEqual('FAIL', result['result'])
        self.assertEqual(1, result['passed_tests'])
        self.assertEqual([{'method': 'testTwo', 'status_code': -2}], result['failed_methods'])

    def test_started_only_does_not_count_as_pass(self):
        result = instrumentation_result(record('testOne', 1) + 'OK (1 test)\n', ['testOne'])
        self.assertEqual('FAIL', result['result'])
        self.assertEqual(0, result['passed_tests'])

    def test_missing_or_duplicate_completion_fails(self):
        for out in (record('testOne', 0) + 'OK (2 tests)\n',
                    record('testOne', 0) * 2 + 'OK (2 tests)\n'):
            self.assertEqual('FAIL', instrumentation_result(out, ['testOne', 'testTwo'])['result'])

    def test_error_and_skip_are_not_passes(self):
        for code in (-1, -3, -4):
            result = instrumentation_result(record('testOne', code) + 'OK (1 test)\n', ['testOne'])
            self.assertEqual('FAIL', result['result'])
            self.assertEqual(0, result['passed_tests'])

    def test_crash_marker_prevents_pass(self):
        out = record('testOne', 0) + 'OK (1 test)\nProcess crashed\n'
        self.assertEqual('FAIL', instrumentation_result(out, ['testOne'])['result'])


if __name__ == '__main__':
    unittest.main()
