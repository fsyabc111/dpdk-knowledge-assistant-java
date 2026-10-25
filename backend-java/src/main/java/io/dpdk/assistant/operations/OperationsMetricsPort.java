package io.dpdk.assistant.operations;

import java.util.List;
import java.util.Map;

/** 只读领域指标快照；消费者不复制 SQL。缺失指标必须显式列 unavailable，禁止填假零。 */
@FunctionalInterface
public interface OperationsMetricsPort {
    record Sample(String name, Map<String, String> labels, double value) {
        public Sample { labels = Map.copyOf(labels); }
    }
    record Snapshot(List<Sample> samples, List<String> unavailable) {
        public Snapshot { samples = List.copyOf(samples); unavailable = List.copyOf(unavailable); }
    }
    Snapshot snapshot();
}
