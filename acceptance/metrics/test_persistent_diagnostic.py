"""语义validator纯文本正反例；不访问PG/HTTP，不覆盖raw strict verdict。"""
import json
from pathlib import Path
import unittest
import sys
sys.path.insert(0,str(Path(__file__).parent))
from persistent_diagnostic import MetricsError,parse,validate
ROOT=Path(__file__).parent
class HistoricalGaugeDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.text=(ROOT/'fixtures/known-and-unknown.prom').read_text()
        self.expect=json.loads((ROOT/'fixtures/known-and-unknown.expectations.json').read_text())
    def test_known_values_unknown_positive_and_unavailable_markers(self):
        result=validate(self.text,expectations=self.expect)
        self.assertEqual(result['known_value_checks'],10);self.assertEqual(result['unavailable0_count'],12)
        self.assertEqual(result['positive_unknown_groups'],1);self.assertFalse(result['raw_wire_equivalence'])
    def test_wrong_gauge_type_is_rejected(self):
        with self.assertRaises(MetricsError):validate(self.text.replace('# TYPE assistant_rag_outcomes_total gauge','# TYPE assistant_rag_outcomes_total counter'))
    def test_content_or_user_labels_are_rejected(self):
        for old,new in [('reason="no_evidence"','question="secret"'),('method="GET"','method="SECRET_METHOD"'),('status="200"','status="2xx"')]:
            with self.assertRaises(MetricsError):validate(self.text.replace(old,new))
    def test_unknown_zero_missing_or_null_zero_fill_are_rejected(self):
        needle='assistant_upstream_usage_unknown_total{stage="answer",kind="llm"} 1'
        for replacement in (needle[:-1]+'0',''):
            with self.assertRaises(MetricsError):validate(self.text.replace(needle,replacement),expectations=self.expect)
        with self.assertRaises(MetricsError):validate(self.text+'assistant_upstream_usage_tokens_total{stage="answer",kind="llm",source="unknown"} 0\n',expectations=self.expect)
    def test_missing_unavailable_or_false_available_are_rejected(self):
        needle='assistant_java_domain_metric_available{metric="assistant_llm_requests_total"} 0'
        for replacement in ('',needle[:-1]+'1'):
            with self.assertRaises(MetricsError):validate(self.text.replace(needle,replacement))
    def test_fake_python_family_cannot_pad_family_count(self):
        with self.assertRaises(MetricsError):validate(self.text+'# TYPE python_info gauge\npython_info 0\n')
    def test_known_value_mismatch_http_count_or_pending_outcome_rejected(self):
        for old,new in [('source="upstream"} 21','source="upstream"} 20'),('seconds_count{method="GET",status="200"} 3','seconds_count{method="GET",status="200"} 4'),('status="unanswered"','status="pending"')]:
            with self.assertRaises(MetricsError):validate(self.text.replace(old,new),expectations=self.expect)
    def test_duplicate_type_series_label_and_nonfinite_values_rejected(self):
        for suffix in ('# TYPE assistant_rag_outcomes_total gauge\n','assistant_rag_outcomes_total{reason="no_evidence",status="unanswered"} 1\n'):
            with self.assertRaises(MetricsError):validate(self.text+suffix)
        with self.assertRaises(MetricsError):parse('metric{a="one",a="two"} 1')
        for value in ('NaN','+Inf','-1'):
            with self.assertRaises(MetricsError):validate(self.text.replace('source="upstream"} 21','source="upstream"} '+value))
    def test_unknown_cost_zero_fill_and_orphan_summary_are_rejected(self):
        with self.assertRaises(MetricsError):validate(self.text+'assistant_upstream_usage_cost_micros_total{stage="answer",kind="llm",model="synthetic",currency="UNKNOWN"} 0\n',expectations=self.expect)
        with self.assertRaises(MetricsError):validate(self.text.replace('assistant_java_http_requests_total{method="GET",status="200"} 3\n',''))
    def test_mapping_covers_frozen_34_without_mutating_the_golden(self):
        mapping=json.loads((ROOT/'persistent-diagnostic-mapping.json').read_text())
        repo=ROOT.resolve().parents[1]
        golden=json.loads((repo/'migration/fixtures/p0-v2/python-golden.json').read_text())
        case=next(x for x in golden['observations'] if x['case_id']=='GET /metrics::success')
        expected={(line.split()[2],line.split()[3]) for line in case['body']['families']}
        self.assertEqual(len(mapping['rows']),34)
        self.assertEqual({(x['source_family'],x['source_type']) for x in mapping['rows']},expected)
    def test_valid_prometheus_label_escaping_is_parsed(self):
        _,samples=parse('metric{reason="a\\\\b\\\"c\\nd"} 1\n')
        self.assertEqual(dict(next(iter(samples.values())).labels)['reason'],'a\\b"c\nd')
    def test_http_error_or_html_content_rejected(self):
        for status,media in ((503,'text/plain'),(200,'text/html')):
            with self.assertRaises(MetricsError):validate(self.text,status=status,media_type=media)
if __name__=='__main__':unittest.main()
