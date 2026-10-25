package io.dpdk.assistant.admin;

/**
 * 批量删除时操作者密码校验失败（对应 Python PermissionError，路由层转
 * 403 {"detail":"password verification failed"}）。
 */
public class ActorPasswordException extends RuntimeException {

    public ActorPasswordException() {
        super("actor password verification failed");
    }
}
