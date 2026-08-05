package decok.dfcdvadstf.imeinput.compat;

import cpw.mods.fml.common.Loader;
import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import journeymap.client.ui.component.JmUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * JourneyMap 兼容层——{@link IMECommitTarget} 的内置实现之一，但与
 * {@link NEICompact} / {@link EMICompact} 不同，它是"全屏治理型"目标：
 * 不提供提交路由（{@link #isActive()} 恒为 false），只负责在 JourneyMap
 * 界面显示期间强制禁用 IME（{@link #isImeBlocked()}）。
 * <p>
 * 背景：JourneyMap 全屏地图（{@code Fullscreen}）在 {@code setWorldAndResolution}
 * 时创建隐藏的 {@code MapChat}（GuiChat 子类），其 {@code initGui} 会新建
 * vanilla {@code GuiTextField} 并置焦，且 {@code setCanLoseFocus(false)} 永不
 * 失焦——Mixin 焦点追踪随之误报"有聚焦文本框"，默认注入路径激活 IME：
 * 候选栏盖在地图上，组字还吞掉 J 等快捷键（再次按 J 无法关闭地图）。
 * 由于焦点残留发生在 vanilla 文本框内部、无屏幕归属可查，最可靠的治理点是
 * 在界面层面整体压制：当前屏幕是 JourneyMap 的 {@link JmUI} 子类（全屏地图、
 * 路标管理、选项等全部 GUI 的基类）时，{@link IMEInputAPI#isImeBlocked()}
 * 返回 true，{@code IMEHandler} 每 tick 的 IME 开关判定直接落空。
 * <p>
 * 类加载安全：静态初始化不触碰任何 journeymap 类；所有 JourneyMap 访问都
 * 发生在 {@link #isLoaded()} 之后（register 前提），未安装 JourneyMap 时不会
 * 触发 journeymap 类加载。
 * <p>
 * JourneyMap compatibility layer — a built-in {@link IMECommitTarget}, but
 * unlike {@link NEICompact} / {@link EMICompact} it is a "fullscreen-governing"
 * target: it offers no commit routing ({@link #isActive()} is always false) and
 * only forcibly disables the IME while a JourneyMap screen is up
 * ({@link #isImeBlocked()}).
 * <p>
 * Background: the JourneyMap fullscreen map ({@code Fullscreen}) creates a
 * hidden {@code MapChat} (a GuiChat subclass) in {@code setWorldAndResolution};
 * its {@code initGui} builds a vanilla {@code GuiTextField}, focuses it and
 * calls {@code setCanLoseFocus(false)}, so it never loses focus — the Mixin
 * focus tracker then wrongly reports "a focused text field" and the default
 * injection path enables the IME: the candidate bar covers the map and
 * composition swallows shortcut keys such as J (pressing J again cannot close
 * the map). Since the stale focus lives inside a vanilla text field with no
 * owning-screen reference to check, the most reliable cure is a screen-level
 * suppression: while the current screen is a {@link JmUI} subclass (the base
 * of every JourneyMap GUI — fullscreen map, waypoint manager, options, ...),
 * {@link IMEInputAPI#isImeBlocked()} returns true and the per-tick IME switch
 * in {@code IMEHandler} simply fails.
 * <p>
 * Class-load safety: the static initializer touches no journeymap class; all
 * JourneyMap access happens after {@link #isLoaded()} (the register
 * prerequisite), so no journeymap class is loaded when JourneyMap is absent.
 */
public final class JourneyMapCompact implements IMECommitTarget {

    /** 单例：注册进 {@link IMEInputAPI} 的实例 */
    private static final JourneyMapCompact INSTANCE = new JourneyMapCompact();

    private JourneyMapCompact() {
    }

    /**
     * JourneyMap 已加载时注册为 IME 提交目标（客户端初始化调用一次）。
     * Registers as an IME commit target when JourneyMap is loaded (call once at
     * client init).
     */
    public static void register() {
        if (isLoaded()) {
            IMEInputAPI.register(INSTANCE);
        }
    }

    /**
     * JourneyMap 是否已加载（modid = {@code journeymap}）。
     * Whether JourneyMap is loaded (modid {@code journeymap}).
     *
     * @return true = 已加载
     */
    public static boolean isLoaded() {
        return Loader.isModLoaded("journeymap");
    }

    @Override
    public boolean isActive() {
        // JourneyMap 无提交目标可路由：其全部文本输入控件（MapChat 的
        // GuiChat.inputField、TextField 继承 vanilla GuiTextField）都走 Mixin
        // 默认注入路径，无需本接口的提交管线。恒 false 仅保留"治理"职责。
        // JourneyMap has no routable commit target: every text input (the
        // GuiChat.inputField of MapChat, TextField extending the vanilla
        // GuiTextField) goes through the Mixin default injection path, so the
        // commit pipeline is never needed. Always false, leaving only the
        // governing role.
        return false;
    }

    @Override
    public void commitText(String text) {
        // 空实现：{@link #isActive()} 恒为 false，本方法永不会被 IMEHandler
        // 路由调用；保留仅为满足接口契约。
        // No-op: {@link #isActive()} is always false so IMEHandler never routes
        // here; kept only to satisfy the interface contract.
    }

    @Override
    public boolean isImeBlocked() {
        // 当前屏幕是 JourneyMap 界面（JmUI 子类）时强制禁用 IME：
        // 全屏地图隐藏聊天框的永不失焦文本框会让默认焦点路径误激活 IME，
        // 候选栏挡图 + 组字吞 J 键导致地图无法关闭；其余界面（路标编辑器、
        // 选项页等）同样整体禁用，统一治理。
        // Forcibly disable the IME while the current screen is a JourneyMap
        // GUI (a JmUI subclass): the fullscreen map's hidden chat text field
        // never loses focus and would spuriously enable the IME via the
        // default focus path — candidate bar over the map and composition
        // swallowing J so the map cannot be closed; other screens (waypoint
        // editor, options, ...) are suppressed the same way for consistency.
        return isJourneyMapScreen(Minecraft.getMinecraft().currentScreen);
    }

    /**
     * 判断当前屏幕是否属于 JourneyMap 界面体系（{@link JmUI} 子类）。
     * Whether the given screen belongs to the JourneyMap GUI family (a
     * {@link JmUI} subclass).
     *
     * @param screen 当前屏幕（可为 null）
     * @return true = JourneyMap 界面
     */
    private static boolean isJourneyMapScreen(GuiScreen screen) {
        return screen instanceof JmUI;
    }
}
