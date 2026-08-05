package decok.dfcdvadstf.imeinput.compat;

import cpw.mods.fml.common.Loader;
import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JourneyMap 兼容层——{@link IMECommitTarget} 的内置实现之一，但与
 * {@link NEICompact} / {@link EMICompact} 不同，它是"全屏治理型"目标：
 * 不提供提交路由（{@link #isActive()} 恒为 false），只负责在 JourneyMap
 * 全屏地图的聊天框隐藏期间强制禁用 IME（{@link #isImeBlocked()}）。
 * <p>
 * 背景：JourneyMap 全屏地图（{@link Fullscreen}）在 {@code setWorldAndResolution}
 * 时创建隐藏的 {@code MapChat}（GuiChat 子类），其 {@code initGui} 会新建
 * vanilla {@code GuiTextField} 并置焦，且 {@code setCanLoseFocus(false)} 永不
 * 失焦——Mixin 焦点追踪随之误报"有聚焦文本框"，默认注入路径激活 IME：
 * 候选栏盖在地图上，组字还吞掉 J 等快捷键（再次按 J 无法关闭地图）。
 * 由于焦点残留发生在 vanilla 文本框内部、无屏幕归属可查，最可靠的治理点是
 * 在界面层面整体压制——但仅限 {@link Fullscreen}：其余 JourneyMap 界面
 * （路标编辑器等）的文本框可见、焦点由玩家主动触发，默认注入路径正常工作，
 * 不应受兼容层影响。
 * <p>
 * 治理粒度精确到聊天框开关：{@code Fullscreen.chat}（反射读取包私有字段，
 * 5.2.20 发布 jar 保留 MCP 字段名）隐藏（或实例缺失）时阻断 IME——隐藏聊天
 * 框的永不失焦文本框正是误激活源；chat 可见（玩家正在全屏地图里聊天）时
 * 放行——此时 inputField 真实可见，IME 应照常工作，与 vanilla 聊天框行为一致。
 * 反射失败（版本差异等）一律按"隐藏"保守处理：宁可牺牲聊天框内的 IME，
 * 也不能让候选栏挡住地图、J 键关不掉界面的 bug 复现。
 * <p>
 * 类加载安全：静态初始化不触碰任何 journeymap 类；所有 JourneyMap 访问都
 * 发生在 {@link #isLoaded()} 之后（register 前提），未安装 JourneyMap 时不会
 * 触发 journeymap 类加载。
 * <p>
 * JourneyMap compatibility layer — a built-in {@link IMECommitTarget}, but
 * unlike {@link NEICompact} / {@link EMICompact} it is a "fullscreen-governing"
 * target: it offers no commit routing ({@link #isActive()} is always false) and
 * only forcibly disables the IME while the JourneyMap fullscreen map's chat box
 * is hidden ({@link #isImeBlocked()}).
 * <p>
 * Background: the JourneyMap fullscreen map ({@link Fullscreen}) creates a
 * hidden {@code MapChat} (a GuiChat subclass) in {@code setWorldAndResolution};
 * its {@code initGui} builds a vanilla {@code GuiTextField}, focuses it and
 * calls {@code setCanLoseFocus(false)}, so it never loses focus — the Mixin
 * focus tracker then wrongly reports "a focused text field" and the default
 * injection path enables the IME: the candidate bar covers the map and
 * composition swallows shortcut keys such as J (pressing J again cannot close
 * the map). Since the stale focus lives inside a vanilla text field with no
 * owning-screen reference to check, the most reliable cure is a screen-level
 * suppression — but only for {@link Fullscreen}: other JourneyMap screens
 * (waypoint editor, ...) host visible text fields whose focus is user-driven,
 * the default injection path works fine there and must not be affected.
 * <p>
 * The suppression is refined down to the chat toggle: while
 * {@code Fullscreen.chat} (read reflectively; the 5.2.20 release jar keeps the
 * MCP field name) is hidden (or absent) the IME is blocked — that never-losing-
 * focus text field is exactly the spurious activation source; while the chat is
 * visible (the player is actually typing in the fullscreen map) the IME is
 * allowed — the inputField is genuinely on screen and IME input should behave
 * like the vanilla chat box. Any reflection failure (version drift) falls back
 * to "hidden": better to lose IME inside the chat than to let the candidate
 * bar cover the map and the J key stop closing the screen.
 * <p>
 * Class-load safety: the static initializer touches no journeymap class; all
 * JourneyMap access happens after {@link #isLoaded()} (the register
 * prerequisite), so no journeymap class is loaded when JourneyMap is absent.
 */
public final class JourneyMapCompact implements IMECommitTarget {

    /** 单例：注册进 {@link IMEInputAPI} 的实例 */
    private static final JourneyMapCompact INSTANCE = new JourneyMapCompact();

    /** {@code Fullscreen.chat}（包私有 MapChat 字段）反射句柄；null 表示未解析或解析失败 */
    private static Field chatField = null;
    /** 是否已完成字段解析（防止反复抛异常） */
    private static boolean chatFieldDone = false;
    /** {@code MapChat.isHidden()}（public）反射句柄；null 表示未解析或解析失败 */
    private static Method isHiddenMethod = null;
    /** 是否已完成方法解析（防止反复抛异常） */
    private static boolean isHiddenDone = false;

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
    public boolean isImeBlocked() {
        // 仅治理 Fullscreen 全屏地图：其余 JourneyMap 界面（JmUI 子类，
        // 路标编辑器等）的文本框可见、焦点由玩家主动触发，Mixin 默认路径
        // 无残留焦点问题，放行以免误伤正常输入。
        // Govern only the Fullscreen map: other JourneyMap screens (JmUI
        // subclasses such as the waypoint editor) host visible, user-focused
        // text fields with no stale-focus hazard on the default path, so they
        // pass through untouched.
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof Fullscreen)) {
            return false;
        }
        // Fullscreen：chat 隐藏（或不可判定）时阻断——隐藏聊天框的永不失焦
        // 文本框是误激活源；chat 可见（玩家正在全屏地图里聊天）时放行。
        // Block while the chat is hidden (or undeterminable) — its never-losing-
        // focus text field is the spurious-activation source; allow while the
        // chat is visible (the player is typing in the fullscreen map).
        return isMapChatHidden((Fullscreen) screen);
    }

    @Override
    public void commitText(String text) {
        // 空实现：{@link #isActive()} 恒为 false，本方法永不会被 IMEHandler
        // 路由调用；保留仅为满足接口契约。
        // No-op: {@link #isActive()} is always false so IMEHandler never routes
        // here; kept only to satisfy the interface contract.
    }

    /**
     * 反射读取 {@code Fullscreen.chat}（包私有）——5.2.20 发布 jar 保留 MCP
     * 字段名，反射不受 reobf 影响。解析失败（版本差异等）返回 null，调用方
     * 按"隐藏"保守处理。Reads {@code Fullscreen.chat} (package-private) via
     * reflection — the 5.2.20 release jar keeps the MCP field name, so
     * reflection is reobf-safe. On failure (e.g. a version mismatch) returns
     * null and the caller falls back to "hidden".
     *
     * @param screen Fullscreen 实例
     * @return MapChat 实例；解析失败返回 null
     */
    private static Object resolveChat(Fullscreen screen) {
        try {
            if (chatField == null && !chatFieldDone) {
                chatField = Fullscreen.class.getDeclaredField("chat");
                chatField.setAccessible(true);
                chatFieldDone = true;
            }
            if (chatField != null) {
                return chatField.get(screen);
            }
        } catch (Throwable t) {
            // 字段解析失败：保守阻断，不阻碍 IME 主流程
            // Field resolution failed: fall back to blocking conservatively
            chatFieldDone = true;
            chatField = null;
        }
        return null;
    }

    /**
     * 判断 Fullscreen 的聊天框当前是否隐藏。chat 实例缺失或反射失败一律
     * 按"隐藏"处理（阻断 IME）——宁可牺牲聊天框内的 IME，也不能让候选栏
     * 挡住地图、J 键关不掉界面的 bug 复现。Whether the Fullscreen chat box is
     * currently hidden. A missing chat instance or any reflection failure
     * counts as "hidden" (IME blocked) — better to lose IME inside the chat
     * than to let the candidate bar cover the map and the J key stop closing
     * the screen.
     *
     * @param screen Fullscreen 实例
     * @return true = chat 隐藏或不可判定（应阻断 IME）
     */
    private static boolean isMapChatHidden(Fullscreen screen) {
        Object chat = resolveChat(screen);
        if (chat == null) {
            // 无 chat 实例（理论不会：setWorldAndResolution 总会创建）或反射失败
            // No chat instance (should not happen: setWorldAndResolution always
            // creates one) or reflection failed
            return true;
        }
        try {
            if (isHiddenMethod == null && !isHiddenDone) {
                isHiddenMethod = chat.getClass().getMethod("isHidden");
                isHiddenDone = true;
            }
            if (isHiddenMethod != null) {
                return (Boolean) isHiddenMethod.invoke(chat);
            }
        } catch (Throwable t) {
            // 方法解析失败：保守阻断 / Method resolution failed: block conservatively
            isHiddenDone = true;
            isHiddenMethod = null;
        }
        return true;
    }
}
