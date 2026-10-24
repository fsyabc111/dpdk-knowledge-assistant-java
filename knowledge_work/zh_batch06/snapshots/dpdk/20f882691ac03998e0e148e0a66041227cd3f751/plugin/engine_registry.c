/**
 * 协议解析引擎 - 注册中心实现
 */

#include "engine.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dlfcn.h>

extern int g_log_level __attribute__((weak));
static inline int registry_log_level(void) { return (&g_log_level ? g_log_level : 1); }

/* ========== 插件事件回调 ========== */

static void (*s_plugin_event_cb)(const char *name, int loaded) = NULL;

void engine_registry_set_plugin_event_cb(
    void (*cb)(const char *name, int loaded))
{
    s_plugin_event_cb = cb;
}

/* ========== 插件管理 ========== */

typedef struct plugin_handle {
    void *dl_handle;
    char name[ENGINE_NAME_MAX_LEN];
    struct plugin_handle *next;
} plugin_handle_t;

/* ========== 内部函数 ========== */


static void insert_to_chain(engine_registry_t *registry, engine_entry_t *entry)
{
    if (!registry->chain_head || 
        entry->ops->priority < registry->chain_head->ops->priority) {
        entry->next = registry->chain_head;
        registry->chain_head = entry;
        return;
    }
    
    engine_entry_t *curr = registry->chain_head;
    while (curr->next && curr->next->ops->priority <= entry->ops->priority) {
        curr = curr->next;
    }
    
    entry->next = curr->next;
    curr->next = entry;
}

static void remove_from_chain(engine_registry_t *registry, engine_entry_t *entry)
{
    if (registry->chain_head == entry) {
        registry->chain_head = entry->next;
        return;
    }
    
    engine_entry_t *curr = registry->chain_head;
    while (curr && curr->next != entry) {
        curr = curr->next;
    }
    
    if (curr) {
        curr->next = entry->next;
    }
}

/* ========== API 实现 ========== */

engine_registry_t *engine_registry_create(void)
{
    engine_registry_t *registry = calloc(1, sizeof(engine_registry_t));
    if (!registry) {
        return NULL;
    }

    registry->engine_count = 0;
    registry->chain_head = NULL;
    registry->plugin_list = NULL;
    pthread_rwlock_init(&registry->registry_lock, NULL);

    return registry;
}

int engine_registry_register(engine_registry_t *registry, const engine_ops_t *ops)
{
    if (!registry || !ops || !ops->name) {
        return ENGINE_ERROR;
    }

    pthread_rwlock_wrlock(&registry->registry_lock);

    if (registry->engine_count >= ENGINE_MAX_ENGINES) {
        fprintf(stderr, "Engine registry full\n");
        pthread_rwlock_unlock(&registry->registry_lock);
        return ENGINE_ERROR;
    }

    // 检查是否已注册（在锁内直接搜索）
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        if (strcmp(registry->engines[i]->ops->name, ops->name) == 0) {
            fprintf(stderr, "Engine '%s' already registered\n", ops->name);
            pthread_rwlock_unlock(&registry->registry_lock);
            return ENGINE_ERROR;
        }
    }

    // 创建引擎条目
    engine_entry_t *entry = calloc(1, sizeof(engine_entry_t));
    if (!entry) {
        pthread_rwlock_unlock(&registry->registry_lock);
        return ENGINE_ERROR;
    }

    entry->ops = ops;
    entry->next = NULL;
    memset(&entry->ctx, 0, sizeof(engine_ctx_t));

    // 初始化引擎
    if (ops->init && ops->init(&entry->ctx) != ENGINE_OK) {
        fprintf(stderr, "Failed to initialize engine '%s'\n", ops->name);
        free(entry);
        pthread_rwlock_unlock(&registry->registry_lock);
        return ENGINE_ERROR;
    }

    // 添加到数组
    registry->engines[registry->engine_count++] = entry;

    // 插入到链表
    insert_to_chain(registry, entry);

    pthread_rwlock_unlock(&registry->registry_lock);

    printf("Engine '%s' registered (priority: %u)\n", ops->name, ops->priority);

    return ENGINE_OK;
}

int engine_registry_unregister(engine_registry_t *registry, const char *name)
{
    if (!registry || !name) {
        return ENGINE_ERROR;
    }

    pthread_rwlock_wrlock(&registry->registry_lock);

    /* 在锁内直接搜索，避免嵌套调用 engine_registry_find */
    engine_entry_t *entry = NULL;
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        if (strcmp(registry->engines[i]->ops->name, name) == 0) {
            entry = registry->engines[i];
            remove_from_chain(registry, entry);
            if (entry->ops->cleanup) {
                entry->ops->cleanup(&entry->ctx);
            }
            for (uint32_t j = i; j < registry->engine_count - 1; j++) {
                registry->engines[j] = registry->engines[j + 1];
            }
            registry->engine_count--;
            free(entry);
            pthread_rwlock_unlock(&registry->registry_lock);
            return ENGINE_OK;
        }
    }

    pthread_rwlock_unlock(&registry->registry_lock);
    return ENGINE_ERROR;
}

engine_entry_t *engine_registry_find(engine_registry_t *registry, const char *name)
{
    if (!registry || !name) {
        return NULL;
    }
    
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        if (strcmp(registry->engines[i]->ops->name, name) == 0) {
            return registry->engines[i];
        }
    }
    
    return NULL;
}

int engine_process(engine_registry_t *registry,
                   session_entry_t *session,
                   const parsed_packet_t *pkt)
{
    if (!registry || !pkt) {
        return ENGINE_ERROR;
    }

    pthread_rwlock_rdlock(&registry->registry_lock);

    engine_entry_t *curr = registry->chain_head;

    while (curr) {
        curr->ctx.stats.packets_processed++;

        // 检查是否能处理
        if (curr->ops->can_handle &&
            curr->ops->can_handle(&curr->ctx, session, pkt)) {

            // 处理数据包
            int ret = curr->ops->process(&curr->ctx, session, pkt);

            if (ret == ENGINE_HANDLED) {
                curr->ctx.stats.packets_matched++;
                pthread_rwlock_unlock(&registry->registry_lock);
                return ENGINE_OK;
            } else if (ret == ENGINE_ERROR) {
                curr->ctx.stats.errors++;
                pthread_rwlock_unlock(&registry->registry_lock);
                return ENGINE_ERROR;
            }
            // ENGINE_CONTINUE: 继续下一个引擎
        }

        curr = curr->next;
    }

    pthread_rwlock_unlock(&registry->registry_lock);
    return ENGINE_OK;
}

int engine_get_all_stats(engine_registry_t *registry,
                         engine_stats_t *stats,
                         uint32_t max_count)
{
    if (!registry || !stats) {
        return 0;
    }
    
    uint32_t count = registry->engine_count < max_count ? 
                     registry->engine_count : max_count;
    
    for (uint32_t i = 0; i < count; i++) {
        if (registry->engines[i]->ops->get_stats) {
            registry->engines[i]->ops->get_stats(&registry->engines[i]->ctx, 
                                                 &stats[i]);
        } else {
            stats[i] = registry->engines[i]->ctx.stats;
        }
    }
    
    return count;
}

void engine_registry_destroy(engine_registry_t *registry)
{
    if (!registry) {
        return;
    }

    pthread_rwlock_wrlock(&registry->registry_lock);
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        if (registry->engines[i]->ops->cleanup) {
            registry->engines[i]->ops->cleanup(&registry->engines[i]->ctx);
        }
        free(registry->engines[i]);
    }
    pthread_rwlock_unlock(&registry->registry_lock);
    pthread_rwlock_destroy(&registry->registry_lock);

    free(registry);
}

/* ========== 动态插件加载实现 ========== */

int engine_registry_load_plugin(engine_registry_t *registry,
                                 const char *plugin_path)
{
    if (!registry || !plugin_path) {
        return ENGINE_ERROR;
    }

    // 加载动态库
    void *handle = dlopen(plugin_path, RTLD_LAZY);
    if (!handle) {
        fprintf(stderr, "Failed to load plugin '%s': %s\n",
                plugin_path, dlerror());
        return ENGINE_ERROR;
    }

    // 获取 engine_get_ops 函数
    engine_get_ops_func_t get_ops =
        (engine_get_ops_func_t)dlsym(handle, "engine_get_ops");

    if (!get_ops) {
        fprintf(stderr, "Plugin '%s' missing engine_get_ops(): %s\n",
                plugin_path, dlerror());
        dlclose(handle);
        return ENGINE_ERROR;
    }

    // 获取引擎操作表
    const engine_ops_t *ops = get_ops();
    if (!ops || !ops->name) {
        fprintf(stderr, "Plugin '%s' returned invalid ops\n", plugin_path);
        dlclose(handle);
        return ENGINE_ERROR;
    }

    /* 写锁保护注册和 plugin_list 修改 */
    pthread_rwlock_wrlock(&registry->registry_lock);

    /* 检查是否已注册（在锁内直接搜索，避免嵌套锁） */
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        if (strcmp(registry->engines[i]->ops->name, ops->name) == 0) {
            fprintf(stderr, "Engine '%s' already registered\n", ops->name);
            pthread_rwlock_unlock(&registry->registry_lock);
            dlclose(handle);
            return ENGINE_ERROR;
        }
    }

    if (registry->engine_count >= ENGINE_MAX_ENGINES) {
        fprintf(stderr, "Engine registry full\n");
        pthread_rwlock_unlock(&registry->registry_lock);
        dlclose(handle);
        return ENGINE_ERROR;
    }

    // 创建引擎条目
    engine_entry_t *entry = calloc(1, sizeof(engine_entry_t));
    if (!entry) {
        pthread_rwlock_unlock(&registry->registry_lock);
        dlclose(handle);
        return ENGINE_ERROR;
    }

    entry->ops = ops;
    entry->next = NULL;
    memset(&entry->ctx, 0, sizeof(engine_ctx_t));

    // 初始化引擎（在锁内，ops->init 应当是纯初始化，无外部锁依赖）
    if (ops->init && ops->init(&entry->ctx) != ENGINE_OK) {
        fprintf(stderr, "Failed to initialize engine '%s'\n", ops->name);
        free(entry);
        pthread_rwlock_unlock(&registry->registry_lock);
        dlclose(handle);
        return ENGINE_ERROR;
    }

    // 添加到数组并插入链表
    registry->engines[registry->engine_count++] = entry;
    insert_to_chain(registry, entry);

    // 保存插件句柄到 registry->plugin_list
    plugin_handle_t *plugin = malloc(sizeof(plugin_handle_t));
    if (!plugin) {
        /* 回滚注册 */
        remove_from_chain(registry, entry);
        registry->engine_count--;
        if (entry->ops->cleanup) entry->ops->cleanup(&entry->ctx);
        free(entry);
        pthread_rwlock_unlock(&registry->registry_lock);
        dlclose(handle);
        return ENGINE_ERROR;
    }

    plugin->dl_handle = handle;
    strncpy(plugin->name, ops->name, ENGINE_NAME_MAX_LEN - 1);
    plugin->name[ENGINE_NAME_MAX_LEN - 1] = '\0';
    plugin->next = registry->plugin_list;
    registry->plugin_list = plugin;

    pthread_rwlock_unlock(&registry->registry_lock);

    if (registry_log_level() > 0)
        printf("Plugin '%s' loaded from '%s'\n", ops->name, plugin_path);
    if (s_plugin_event_cb) s_plugin_event_cb(ops->name, true);
    return ENGINE_OK;
}

int engine_registry_unload_plugin(engine_registry_t *registry,
                                   const char *name)
{
    if (!registry || !name) {
        return ENGINE_ERROR;
    }

    pthread_rwlock_wrlock(&registry->registry_lock);

    // 查找插件句柄
    plugin_handle_t *prev = NULL;
    plugin_handle_t *curr = registry->plugin_list;

    while (curr) {
        if (strcmp(curr->name, name) == 0) {
            /* 从引擎数组和链表中移除（在锁内直接操作，避免嵌套锁） */
            engine_entry_t *entry = NULL;
            for (uint32_t i = 0; i < registry->engine_count; i++) {
                if (strcmp(registry->engines[i]->ops->name, name) == 0) {
                    entry = registry->engines[i];
                    remove_from_chain(registry, entry);
                    if (entry->ops->cleanup) {
                        entry->ops->cleanup(&entry->ctx);
                    }
                    for (uint32_t j = i; j < registry->engine_count - 1; j++) {
                        registry->engines[j] = registry->engines[j + 1];
                    }
                    registry->engine_count--;
                    free(entry);
                    break;
                }
            }

            // 关闭动态库
            dlclose(curr->dl_handle);

            // 从插件链表移除
            if (prev) {
                prev->next = curr->next;
            } else {
                registry->plugin_list = curr->next;
            }

            pthread_rwlock_unlock(&registry->registry_lock);
            printf("Plugin '%s' unloaded\n", name);
            if (s_plugin_event_cb) s_plugin_event_cb(name, false);
            free(curr);
            return ENGINE_OK;
        }
        prev = curr;
        curr = curr->next;
    }

    pthread_rwlock_unlock(&registry->registry_lock);
    fprintf(stderr, "Plugin '%s' not found\n", name);
    return ENGINE_ERROR;
}
