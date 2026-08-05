package decok.dfcdvadstf.imeinput.api;

import java.util.ArrayList;
import java.util.List;

/**
 * IME 提交目标注册表：模组/前置在初始化阶段（客户端）注册其
 * {@link IMECommitTarget} 实现，本模组的 {@code IMEHandler} 每个客户端 tick
 * 查询一次激活目标并据此路由 IME 提交。
 * <p>
 * 线程模型：所有方法均应在 Minecraft 主线程调用（注册于 FML preInit，
 * 查询于客户端 tick/渲染），内部为无锁 {@link ArrayList}。
 * <p>
 * Registry of IME commit targets: mods/predecessors register their
 * {@link IMECommitTarget} implementations at client init; the mod's
 * {@code IMEHandler} polls the active target once per client tick and routes
 * IME commits accordingly.
 * <p>
 * Threading: all methods must be called on the Minecraft main thread
 * (registration at FML preInit, polling on client tick/render); internally an
 * unsynchronized {@link ArrayList}.
 */
public final class IMEInputAPI {

    /** 已注册目标（注册顺序即优先级：先注册者先被查询） */
    private static final List<IMECommitTarget> targets = new ArrayList<IMECommitTarget>(2);

    private IMEInputAPI() {
    }

    /**
     * 注册提交目标。重复注册同一实例是空操作；注册后目标即刻参与每 tick 的
     * 激活查询，无需其他通知。
     * Registers a commit target. Re-registering the same instance is a no-op;
     * the target immediately takes part in the per-tick activation poll.
     *
     * @param target 目标实例（非 null）
     */
    public static void register(IMECommitTarget target) {
        if (target != null && !targets.contains(target)) {
            targets.add(target);
        }
    }

    /**
     * 注销提交目标（客户端生命周期内通常无需调用）。
     * Unregisters a commit target (normally not needed during a client session).
     *
     * @param target 目标实例
     */
    public static void unregister(IMECommitTarget target) {
        targets.remove(target);
    }

    /**
     * 返回第一个 {@link IMECommitTarget#isActive()} 为 true 的已注册目标；
     * 无激活目标时返回 null。每 tick 调用一次即可。
     * Returns the first registered target whose {@link IMECommitTarget#isActive()}
     * is true, or null if none is active. Poll once per tick.
     *
     * @return 激活目标；无则 null
     */
    public static IMECommitTarget getActiveTarget() {
        for (IMECommitTarget target : targets) {
            if (target.isActive()) {
                return target;
            }
        }
        return null;
    }

    /**
     * 是否有已注册目标要求当前强制禁用 IME（如 JourneyMap 全屏地图界面——
     * 其隐藏聊天框的 vanilla 文本框永不失焦，默认焦点路径会误激活 IME）。
     * 每 tick 在 IME 开关判定前查询一次；任一目标返回 true 即整体禁用。
     * Whether any registered target demands the IME be forcibly disabled
     * (e.g. the JourneyMap fullscreen map — its hidden chat widget's vanilla
     * text field never loses focus, so the default focus path would spuriously
     * enable the IME). Poll once per tick before the IME switch decision; any
     * target returning true disables the IME entirely.
     *
     * @return true = 至少一个目标要求禁用 IME
     */
    public static boolean isImeBlocked() {
        for (IMECommitTarget target : targets) {
            if (target.isImeBlocked()) {
                return true;
            }
        }
        return false;
    }
}
