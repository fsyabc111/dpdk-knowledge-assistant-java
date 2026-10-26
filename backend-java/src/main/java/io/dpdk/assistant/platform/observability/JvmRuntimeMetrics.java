package io.dpdk.assistant.platform.observability;

import java.lang.management.ManagementFactory;

/** JVM真实可用观测；不伪造Python代际GC对象回收/不可回收数量。 */
final class JvmRuntimeMetrics {
    void append(StringBuilder out) {
        out.append("# HELP jvm_gc_collections_total Actual JVM GC collection events, not collected Python objects.\n# TYPE jvm_gc_collections_total counter\n");
        for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean.getCollectionCount()>=0)
            out.append("jvm_gc_collections_total{collector=\"").append(ProcessMetricsRegistry.escape(bean.getName())).append("\"} ").append(bean.getCollectionCount()).append('\n');
        out.append("# HELP jvm_gc_collection_seconds_total Actual JVM GC collection elapsed time in seconds.\n# TYPE jvm_gc_collection_seconds_total counter\n");
        for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean.getCollectionTime()>=0)
            out.append("jvm_gc_collection_seconds_total{collector=\"").append(ProcessMetricsRegistry.escape(bean.getName())).append("\"} ").append(bean.getCollectionTime()/1000.0).append('\n');
        out.append("# HELP jvm_memory_used_bytes Actual heap/nonheap used bytes; not an uncollectable object count.\n# TYPE jvm_memory_used_bytes gauge\n");
        var memory=ManagementFactory.getMemoryMXBean();
        out.append("jvm_memory_used_bytes{area=\"heap\"} ").append(memory.getHeapMemoryUsage().getUsed()).append('\n');
        out.append("jvm_memory_used_bytes{area=\"nonheap\"} ").append(memory.getNonHeapMemoryUsage().getUsed()).append('\n');
        out.append("# HELP jvm_info Actual Java version, vendor and VM.\n# TYPE jvm_info gauge\n");
        out.append("jvm_info{version=\"").append(ProcessMetricsRegistry.escape(System.getProperty("java.version")))
                .append("\",vendor=\"").append(ProcessMetricsRegistry.escape(System.getProperty("java.vendor")))
                .append("\",runtime=\"").append(ProcessMetricsRegistry.escape(System.getProperty("java.vm.name"))).append("\"} 1\n");
    }
}
