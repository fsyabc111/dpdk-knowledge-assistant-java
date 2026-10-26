"""CPU窗口结束后运行；正式过程指标反例，不接受旧gauge/unavailable豁免。"""
import json
from pathlib import Path
import unittest
import sys
sys.path.insert(0,str(Path(__file__).parent))
from validate import MetricsError,validate,SPEC
ROOT=Path(__file__).parent

class SourceProcessValidationTest(unittest.TestCase):
    def setUp(self):
        sys.path.insert(0,str(ROOT/'fixtures'))
        from process_fixture import synthetic_text
        self.text=synthetic_text()
        self.expected=json.loads((ROOT/'fixtures/process-events.expectations.json').read_text())
    def test_registered_source_families_and_real_event_values(self):
        result=validate(self.text,expectations=self.expected)
        self.assertEqual(result['domain_families_registered'],16)
        self.assertEqual(result['domain_families_observed'],16)
        self.assertFalse(result['persisted_gauge_or_unavailable_used'])
    def test_old_persisted_gauge_and_unavailable_are_rejected(self):
        with self.assertRaises(MetricsError):validate((ROOT/'fixtures/known-and-unknown.prom').read_text())
        with self.assertRaises(MetricsError):validate(self.text+'# TYPE assistant_java_domain_metric_available gauge\nassistant_java_domain_metric_available{metric="assistant_llm_requests_total"} 0\n')
    def test_source_counter_and_histogram_types_are_mandatory(self):
        for before,after in [('assistant_upstream_usage_tokens_total counter','assistant_upstream_usage_tokens_total gauge'),('assistant_rag_duration_seconds histogram','assistant_rag_duration_seconds summary')]:
            with self.assertRaises(MetricsError):validate(self.text.replace(before,after))
    def test_source_bucket_bounds_cumulative_counts_and_inf_are_validated(self):
        with self.assertRaises(MetricsError):validate(self.text.replace('le="240.0"','le="241.0"'))
        target='assistant_rag_duration_seconds_bucket{le="+Inf"} 1.0'
        with self.assertRaises(MetricsError):validate(self.text.replace(target,target[:-3]+'0.0'))
    def test_creation_metadata_is_for_the_actual_child_not_static_padding(self):
        target='assistant_llm_requests_created{engine="llama_index",result="success"} 1000.0\n'
        with self.assertRaises(MetricsError):validate(self.text.replace(target,''))
        with self.assertRaises(MetricsError):validate(self.text+'assistant_llm_requests_created{engine="NOT_OBSERVED",result="success"} 1000.0\n')
    def test_unobserved_type_alone_does_not_prove_real_events(self):
        expected=dict(self.expected);expected['samples']=[];expected['absent_samples']=[];expected['exact_series_families']=[]
        with self.assertRaises(MetricsError):validate(self.text.replace('assistant_rate_limit_hits_total{kind="per_minute"} 1.0','assistant_rate_limit_hits_total{kind="per_minute"} 0.0'),expectations=expected)
    def test_unknown_cannot_be_zeroed_and_known_zero_tokens_cannot_be_fabricated(self):
        target='assistant_upstream_usage_unknown_total{stage="answer",kind="llm"} 1.0'
        with self.assertRaises(MetricsError):validate(self.text.replace(target,target[:-3]+'0.0'),expectations=self.expected)
        with self.assertRaises(MetricsError):validate(self.text+'assistant_upstream_usage_tokens_total{stage="answer",kind="llm",source="unknown"} 0.0\n',expectations=self.expected)
    def test_runtime_does_not_pretend_python_gc_object_counts(self):
        with self.assertRaises(MetricsError):validate(self.text+'# TYPE python_gc_objects_collected_total counter\npython_gc_objects_collected_total{generation="0"} 0\n')
    def test_labels_registration_order_and_http_grouping_are_not_relaxed(self):
        with self.assertRaises(MetricsError):validate(self.text.replace('status="2xx"','status="200"'))
        with self.assertRaises(MetricsError):validate(self.text.replace('kind="per_minute"','user="PRIVATE"'))
        old='# TYPE assistant_llm_requests_total counter';new='# TYPE assistant_llm_request_duration_seconds histogram'
        with self.assertRaises(MetricsError):validate(self.text.replace(old,'TEMP_SWAP').replace(new,old).replace('TEMP_SWAP',new))
    def test_help_names_do_not_change_unicode_codepoint_unit_to_utf8_bytes(self):
        row=next(x for x in SPEC['domain'] if x['name']=='assistant_agent_tool_result_bytes')
        self.assertIn('Unicode codepoints',row['unit_note'])

if __name__=='__main__':unittest.main()
