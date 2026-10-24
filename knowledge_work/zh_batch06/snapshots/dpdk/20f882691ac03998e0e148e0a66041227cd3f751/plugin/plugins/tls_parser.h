#ifndef TLS_PARSER_H
#define TLS_PARSER_H

#include <stdint.h>
#include <stddef.h>


#ifdef __cplusplus
extern "C" {
#endif


#define MAX_SNI_LENGTH 256
#define MAX_CIPHER_SUITES 128
#define MAX_EXTENSIONS 64

// TLS ClientHello信息结构
typedef struct {
    /* TLS 版本号：若 ClientHello 带 supported_versions 扩展（TLS 1.3 必带），
     * 取扩展中最高的非 GREASE 版本（代表客户端声明的最大支持版本）；
     * 否则回退到 record-layer legacy_version。
     * 可能值：0x0301 / 0x0302 / 0x0303 / 0x0304 等。 */
    uint16_t tls_version;
    char sni[MAX_SNI_LENGTH];                // SNI (Server Name Indication)
    uint16_t cipher_suites[MAX_CIPHER_SUITES]; // Cipher Suites列表
    uint16_t cipher_suites_count;            // Cipher Suites数量
    uint16_t extensions[MAX_EXTENSIONS];     // Extensions列表
    uint16_t extensions_count;               // Extensions数量
    int is_valid;                            // 是否成功解析
} tls_client_hello_t;

/**
 * 解析TLS ClientHello包
 * @param payload TLS数据包载荷
 * @param length 载荷长度
 * @param hello 输出的ClientHello信息结构
 * @return 0成功，-1失败
 */
int parse_tls_client_hello(const uint8_t *payload, uint32_t length, tls_client_hello_t *hello);

/**
 * 从TLS ClientHello中提取SNI
 * @param payload TLS数据包载荷
 * @param length 载荷长度
 * @param sni 输出的SNI字符串
 * @param sni_size SNI缓冲区大小
 * @return 0成功，-1失败
 */
int extract_sni_from_client_hello(const uint8_t *payload, uint32_t length,
                                   char *sni, size_t sni_size);

/* TLS ServerHello 信息 —— 仅抽取 TLS 版本号和服务端协商选定的单一密码套件 */
typedef struct {
    /* 真实协商版本：TLS 1.3 ServerHello 的 legacy_version 固定为 0x0303，
     * 真实版本从 supported_versions 扩展（2 字节扩展体）读取；其它版本直接使用
     * legacy_version。可能值：0x0301 / 0x0302 / 0x0303 / 0x0304。 */
    uint16_t tls_version;
    uint16_t cipher_suite;  /* 服务端最终选定的唯一套件 ID */
    int      is_valid;
} tls_server_hello_t;

/**
 * 解析 TLS ServerHello 数据包，提取服务端选择的单一密码套件。
 * @param payload TLS 数据包载荷（应以 0x16 开头，Handshake 类型为 0x02）
 * @param length  载荷长度
 * @param info    输出的 ServerHello 信息
 * @return 0 成功，-1 失败
 */
int parse_tls_server_hello(const uint8_t *payload, uint32_t length,
                           tls_server_hello_t *info);

/**
 * 查询指定的 TLS 密码套件 ID 对应的标准名称（IANA 注册名）。
 * 返回的字符串为静态存储，调用方无需释放。
 * 对于未收录的 ID，返回形如 "CIPHER_0xAABB" 的占位名称。
 */
const char *tls_cipher_suite_name(uint16_t id);

#ifdef __cplusplus
}
#endif

#endif // TLS_PARSER_H
