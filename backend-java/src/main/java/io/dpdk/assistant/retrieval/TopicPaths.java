package io.dpdk.assistant.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题词→路径片段映射（逐项移植 backend/app/services/retrieval_service.py 的
 * TOPIC_PATH_KEYWORDS 与 SPECIFIC_TOPIC_KEYWORDS；键序保持源插入序，
 * topic_boost 扫描顺序与 Python dict 迭代一致）。
 */
public final class TopicPaths {

    /** 不可变视图；LinkedHashMap 保序。 */
    public static final Map<String, List<String>> TOPIC_PATH_KEYWORDS;
    /** 特定主题词（命中应压过通用协议词）。 */
    public static final Set<String> SPECIFIC_TOPIC_KEYWORDS;

    static {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("ftp", java.util.List.of("engine_ftp.c", "ftp_pasv_registry"));
        map.put("smtp", java.util.List.of("engine_smtp.c"));
        map.put("pop3", java.util.List.of("engine_pop3.c"));
        map.put("imap", java.util.List.of("engine_imap.c"));
        map.put("ssh", java.util.List.of("engine_ssh.c"));
        map.put("dns", java.util.List.of("engine_dns.cpp", "dns_ext.h"));
        map.put("dtls", java.util.List.of("engine_dtls.cpp"));
        map.put("tls", java.util.List.of("tls_plugin.c", "tls_parser", "engine_tls_infra.cpp", "ja3_fingerprint", "cert_plugin", "cert_extractor.c", "https_plugin.c", "https_identifier"));
        map.put("证书", java.util.List.of("cert_extractor.c", "cert_plugin", "tls_parser", "ja3_fingerprint"));
        map.put("sni", java.util.List.of("cert_extractor.c", "tls_parser", "cert_plugin"));
        map.put("san", java.util.List.of("cert_extractor.c", "tls_parser", "cert_plugin"));
        map.put("quic", java.util.List.of("engine_quic.c"));
        map.put("http", java.util.List.of("http_plugin.c", "engine_http.c", "http_parser"));
        map.put("https", java.util.List.of("https_plugin.c", "https_identifier", "tls_plugin.c", "cert_plugin"));
        map.put("rdp", java.util.List.of("engine_rdp.cpp"));
        map.put("rtmp", java.util.List.of("engine_rtmp.cpp"));
        map.put("rtsp", java.util.List.of("engine_rtsp.c"));
        map.put("smb", java.util.List.of("engine_smb.cpp"));
        map.put("openvpn", java.util.List.of("engine_openvpn.c"));
        map.put("ipsec", java.util.List.of("engine_ipsecvpn.c"));
        map.put("wireguard", java.util.List.of("engine_wireguard.c"));
        map.put("shadowsocks", java.util.List.of("engine_shadowsocks.c"));
        map.put("v2ray", java.util.List.of("engine_v2ray.c"));
        map.put("template", java.util.List.of("engine_template.c"));
        map.put("auxiliary", java.util.List.of("engine_auxiliary.c"));
        map.put("tunnel", java.util.List.of("engine_tunnel.c"));
        map.put("geo", java.util.List.of("engine_geo_policy.c", "geoip_mmdb"));
        map.put("focus", java.util.List.of("engine_focus_site.cpp"));
        map.put("ranking", java.util.List.of("engine_site_ranking.cpp"));
        map.put("ids", java.util.List.of("engine_ids.cpp"));
        map.put("threat", java.util.List.of("engine_ti.cpp", "ti_intel_manager", "ti_mmap", "domain_intel"));
        map.put("ti", java.util.List.of("engine_ti.cpp", "ti_intel_manager", "ti_mmap"));
        map.put("威胁情报", java.util.List.of("engine_ti.cpp", "ti_intel_manager", "ti_mmap", "domain_intel"));
        map.put("verdict", java.util.List.of("verdict_engine", "verdict_config", "verdict_scoring_config"));
        map.put("storage", java.util.List.of("storage_engine", "ch_storage", "ch_event", "ch_tables", "mongo_storage", "virtual_collection"));
        map.put("clickhouse", java.util.List.of("storage_engine.c", "ch_storage", "ch_event", "ch_tables", "virtual_collection"));
        map.put("session", java.util.List.of("session.c", "session.h"));
        map.put("流表", java.util.List.of("session.c", "session.h"));
        map.put("flow table", java.util.List.of("session.c", "session.h"));
        map.put("会话表", java.util.List.of("session.c", "session.h"));
        map.put("session_entry", java.util.List.of("session.h"));
        map.put("session_entry_t", java.util.List.of("session.h"));
        map.put("结构体", java.util.List.of());
        map.put("capture", java.util.List.of("capture.c", "capture.h", "capture_dpdk.c", "capture_pcap.c"));
        map.put("运行链路", java.util.List.of("main.c", "capture", "session", "engine.h", "engine_registry.c", "stats"));
        map.put("整体运行", java.util.List.of("main.c", "capture", "session", "engine.h", "engine_registry.c", "stats"));
        map.put("数据包处理", java.util.List.of("capture", "parsed_packet", "session", "engine.h", "engine_registry.c"));
        map.put("packet flow", java.util.List.of("main.c", "capture", "parsed_packet", "session", "engine.h"));
        map.put("pipeline", java.util.List.of("main.c", "capture", "session", "engine.h", "stats"));
        map.put("stats", java.util.List.of("stats.c", "stats.h", "stats_types.h"));
        map.put("student_profile", java.util.List.of("student_profile.c", "student_profile.h"));
        map.put("ip_profile", java.util.List.of("student_profile.c", "student_profile.h", "ip_profile_daily_job.c"));
        map.put("event_batch", java.util.List.of("event_batch.c", "event_batch.h"));
        map.put("capacity", java.util.List.of("capacity_manager.c", "capacity_manager.h"));
        map.put("merge", java.util.List.of("merge_logic.c"));
        map.put("hash_table", java.util.List.of("hash_table.c", "hash_table.h"));
        map.put("plugin", java.util.List.of("engine.h", "engine_registry.c", "engine_log"));
        map.put("flask", java.util.List.of("Workspace_Integration_API.py", "workspace_service.py", "app.py", "app_config.py"));
        map.put("workspace", java.util.List.of("Workspace_Integration_API.py", "workspace_service.py"));
        map.put("jwt", java.util.List.of("auth_api.py", "app_config.py"));
        map.put("嵌入", java.util.List.of("vector_math", "retrieval_service", "knowledge_sources"));
        map.put("embedding", java.util.List.of("vector_math", "retrieval_service", "knowledge_sources"));
        map.put("ip画像", java.util.List.of("student_profile.c", "student_profile.h", "ip_profile_daily_job.c"));
        map.put("ip 画像", java.util.List.of("student_profile.c", "student_profile.h", "ip_profile_daily_job.c"));
        map.put("容量管理", java.util.List.of("capacity_manager.c", "capacity_manager.h"));
        map.put("事件批处理", java.util.List.of("event_batch.c", "event_batch.h"));
        map.put("合并逻辑", java.util.List.of("merge_logic.c"));
        map.put("哈希表", java.util.List.of("hash_table.c", "hash_table.h"));
        map.put("state machine", java.util.List.of("engine_pop3.c", "engine_smtp.c", "engine_ssh.c", "engine_openvpn.c"));
        map.put("状态机", java.util.List.of("engine_pop3.c", "engine_smtp.c", "engine_ssh.c", "engine_openvpn.c"));
        TOPIC_PATH_KEYWORDS = Collections.unmodifiableMap(map);
        SPECIFIC_TOPIC_KEYWORDS = Set.of(
                "证书", "sni", "san", "ja3", "威胁情报", "clickhouse", "状态机", "流表",
                "会话表", "ip画像", "容量管理", "事件批处理", "合并逻辑", "哈希表");
    }

    private TopicPaths() {
    }
}
