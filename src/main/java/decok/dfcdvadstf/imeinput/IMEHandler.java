package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import decok.dfcdvadstf.catframe.adapter.forge.event.CatFrameGuiEvent;
import decok.dfcdvadstf.catframe.ui.overlay.OverlayManager;
import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import decok.dfcdvadstf.imeinput.compat.NEICompact;
import decok.dfcdvadstf.imeinput.gui.IMEOverlay;
import decok.dfcdvadstf.imeinput.gui.IMETheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.common.MinecraftForge;
import org.jnilib.imeinput.IMEBridge;
import org.lwjgl.input.Keyboard;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * IME 输入法客户端管理器。
 * <p>
 * 职责：
 * <ol>
 * <li>获取 LWJGL 游戏窗口句柄（反射 {@code WindowsDisplay.current_display.hwnd}）
 * 并安装 IME 后端（AUTO 先试 TSF，失败降级 IMM32 子类化）</li>
 * <li>作为 {@link IMEBridge.IMECallback} 接收组字/提交/候选回调</li>
 * <li>在客户端 tick 中：按聚焦文本框开关 IME、定位候选窗、消费已提交文字</li>
 * </ol>
 * 说明：LWJGL 2.x 的窗口消息在游戏主线程的 {@code Display.update()} 中被泵送，
 * 因此本地回调实际运行在主线程；但仍通过队列在 tick 中统一消费，保证时序与线程安全。
 */
public final class IMEHandler implements IMEBridge.IMECallback {

    /** 单例：本地层持有其全局引用作为回调对象 */
    private static final IMEHandler INSTANCE = new IMEHandler();

    /** 无引用占位，避免频繁创建 */
    private static final WeakReference<GuiTextField> EMPTY = new WeakReference<GuiTextField>(null);

    /** 当前聚焦的文本框（由 Mixin 更新） */
    private static volatile WeakReference<GuiTextField> focusedField = EMPTY;

    /**
     * 当前激活的 IME 提交目标（每 tick 由 {@link IMEInputAPI} 查询并缓存，
     * 供提交路由与候选窗锚点使用；渲染线程与 tick 同在主线程，无并发问题）。
     * Currently active IME commit target, polled and cached each tick for commit
     * routing and the candidate-window anchor (render and tick share the main
     * thread).
     */
    private IMECommitTarget activeTarget = null;

    /** 已提交文字队列（回调线程写入，客户端 tick 消费） */
    private final ConcurrentLinkedQueue<String> commitQueue = new ConcurrentLinkedQueue<String>();

    /** 当前组字快照（对齐 PreeditEvent 形态； null 表示未在组字） */
    private volatile IMEBridge.CompositionInfo compositionInfo = null;

    /** 当前候选页快照（自绘候选窗消费；null 表示候选窗已关闭） */
    private volatile CandidatePage candidatePage = null;

    /** Mixin 渲染时上报的光标 GUI 坐标（组字预览插入点）；-1 表示尚未上报 */
    private volatile int caretGuiX = -1;
    /** 光标底部的 GUI Y 坐标（候选窗紧贴其下方） */
    private volatile int caretGuiY = -1;

    /** 已解析到的窗口句柄（Windows 为 HWND，Linux 为 X Window），0 表示尚未获取 */
    private long hwnd = 0L;
    /** Linux 下的 X {@code Display*} 指针值（随句柄解析一并取得）；Windows 恒为 0 */
    private long xDisplay = 0L;
    /** 是否已完成子类化与回调注册 */
    private boolean installed = false;
    /** 上一次向本地层同步的 IME 使能状态 */
    private boolean lastImeEnabled = false;
    /** 是否已尝试过（失败）获取句柄，避免每 tick 刷屏日志 */
    private boolean handleLookupFailedLogged = false;
    /** Linux 后端安装失败是否已记日志（保持每 tick 重试但只警告一次） */
    private boolean linuxInstallFailedLogged = false;

    /**
     * 按键转发用：按下时记住每个 LWJGL 键码对应的 keysym，释放时原样回放。
     * LWJGL2 的释放事件常不携带字符，若重新映射会丢失 Shift 变体（如 A/a）。
     * Key-forwarding aid: remember the keysym per LWJGL key code at press time
     * and replay it on release — LWJGL2 release events usually carry no char.
     */
    private final int[] pressedKeysyms = new int[256];

    /**
     * 配置的后端模式（{@link IMEBridge#BACKEND_AUTO} / {@code BACKEND_TSF} /
     * {@code BACKEND_IMM32} / {@code BACKEND_IBUS} / {@code BACKEND_FCITX}），
     * 由 {@link #init(int, boolean)} 从 Forge 配置传入。
     * Configured backend mode, passed in from the Forge config via
     * {@link #init(int, boolean)}.
     */
    private static int backendMode = IMEBridge.BACKEND_AUTO;

    /**
     * 配置的原生候选条开关（{@code candidateNativeBar}）：true = 候选窗由
     * 系统/输入法自身绘制（默认）；false = 隐藏原生候选窗，由
     * {@link IMEOverlay} 据候选快照自绘。安装前同步给本地层。
     * Configured native candidate bar switch ({@code candidateNativeBar}):
     * true = the OS/IME draws its own candidate window (default); false =
     * suppress it and let {@link IMEOverlay} self-draw from the snapshots.
     * Synced to the native layer before install.
     */
    private static boolean nativeCandidateBar = true;

    /**
     * 窗口改动侦测基准：上次观察到的全屏标志与窗口尺寸。
     * 三者任一变化才触发句柄复检（窗口重建必伴随其中至少一项变化），
     * 不做周期轮询——玩家一局游戏最多切换那么几次。
     * Window-change detection baseline: last observed fullscreen flag and display
     * size. Only a change in any of them triggers an hwnd revalidation (window
     * recreation always comes with at least one of these changing); no periodic
     * polling since players toggle the window a handful of times per session.
     */
    private boolean lastFullscreen = false;
    private int lastDisplayW = -1;
    private int lastDisplayH = -1;

    /**
     * 待完成的句柄复检：切换中途 resolveHwnd() 可能短暂返回 0，
     * 挂起此旗标在后续 tick 重试直到拿到有效句柄。
     * Pending hwnd recheck: resolveHwnd() may briefly return 0 mid-switch;
     * this flag keeps retrying on later ticks until a valid handle shows up.
     */
    private boolean rehookPending = false;

    private IMEHandler() {
    }

    // ============================================================
    // 数据结构
    // ============================================================

    /**
     * 候选页快照：本地层已按 {@code dwPageStart}/{@code dwPageSize} 切好的当前可见页。
     * 不可变，回调线程构造后整体发布，渲染线程只读。
     * Candidate page snapshot: the visible page pre-sliced by the native layer.
     * Immutable — published as a whole from the callback thread, read-only for
     * rendering.
     */
    public static final class CandidatePage {
        /** 页内候选词文本（顺序即页内序号） / Page candidate texts, in page order */
        public final String[] texts;
        /** 页内选中项索引；-1 表示无 / Page-relative selection index, -1 = none */
        public final int selectedIndex;

        CandidatePage(String[] texts, int selectedIndex) {
            this.texts = texts;
            this.selectedIndex = selectedIndex;
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * 在客户端初始化时调用：记录后端模式与候选条归属、注册 tick 事件监听，
     * 并把自绘组词窗 Overlay 挂入 CatFrame 的 OverlayManager
     * （渲染由 CatFrame 的 DrawScreenEvent 驱动）。
     * 自绘候选开关直接写入 {@link IMEOverlay#selfDrawCandidates}；
     * 主题系统（{@link IMETheme#init}）同步注册资源重载监听，外观由资源包
     * 主题文件驱动；真正的后端安装延迟到第一次 tick（此时 Display 已创建）。
     * Records the backend mode and candidate bar ownership, registers the tick
     * listener and hooks the self-drawn composition overlay into CatFrame's
     * OverlayManager; the self-draw switch is written straight into
     * {@link IMEOverlay#selfDrawCandidates}. The theme system
     * ({@link IMETheme#init}) registers its resource reload listener here, so
     * the overlay look is driven by the resource-pack theme file. The actual
     * backend install is deferred to the first tick (Display exists by then).
     *
     * @param mode      后端模式（{@link IMEBridge#BACKEND_AUTO} / {@code BACKEND_TSF} /
     *                  {@code BACKEND_IMM32} / {@code BACKEND_IBUS} / {@code BACKEND_FCITX}）
     * @param nativeBar true = 原生候选条（默认）；false = 自绘候选框
     */
    public static void init(int mode, boolean nativeBar) {
        backendMode = mode;
        nativeCandidateBar = nativeBar;
        IMEOverlay.selfDrawCandidates = !nativeBar;
        IMETheme.init();
        FMLCommonHandler.instance().bus().register(INSTANCE);
        // CatFrame 的 GuiScreen 键盘事件桥发布在 MinecraftForge.EVENT_BUS（非 FML 总线），
        // 需单独注册以接收 KeyboardInputEvent.Pre（组字期间吞键）。
        // CatFrame's GuiScreen keyboard event bridge posts on MinecraftForge.EVENT_BUS
        // (not the FML bus); register separately for KeyboardInputEvent.Pre.
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        OverlayManager.INSTANCE.register(IMEOverlay.INSTANCE);
    }

    /**
     * 由 Mixin 在文本框获得焦点时调用。
     *
     * @param field 获得焦点的文本框
     */
    public static void setFocusedField(GuiTextField field) {
        focusedField = new WeakReference<GuiTextField>(field);
    }

    /**
     * 由 Mixin 在文本框失去焦点时调用（仅当传入实例正是当前记录实例时清除）。
     *
     * @param field 失去焦点的文本框
     */
    public static void clearFocusedField(GuiTextField field) {
        if (focusedField.get() == field) {
            focusedField = EMPTY;
        }
    }

    /**
     * 当前聚焦的文本框（Mixin 追踪的实例；可能为 null）。
     * 供 IME 提交目标（如 {@link NEICompact}）与聚焦文本框对照判定使用。
     * The currently focused text field tracked by the Mixin (may be null);
     * used by IME commit targets (e.g. {@link NEICompact}) for cross-checks.
     *
     * @return 聚焦文本框；无则 null
     */
    public static GuiTextField getFocusedField() {
        return focusedField.get();
    }

    /**
     * 组字预览串（可能为空字符串）。供 Mixin 渲染下划线预览使用。
     *
     * @return 当前组字串
     */
    public static String getCompositionText() {
        IMEBridge.CompositionInfo info = INSTANCE.compositionInfo;
        return info != null && info.isComposing ? info.composition : "";
    }

    /**
     * 当前组字快照（分段 + 聚焦段 + 光标，对齐 PreeditEvent 形态）。
     * 供 Mixin 渲染分段高亮预览使用。
     * Current composition snapshot (blocks + focused block + caret) for inline
     * rendering.
     *
     * @return 组字快照；未在组字时返回 null
     */
    public static IMEBridge.CompositionInfo getCompositionInfo() {
        IMEBridge.CompositionInfo info = INSTANCE.compositionInfo;
        return info != null && info.isComposing && info.composition.length() > 0 ? info : null;
    }

    /**
     * 由 Mixin 在渲染组字预览时上报光标位置（GUI 逻辑坐标），
     * 供候选窗跟随光标定位；坐标在 tick 中按 guiScale 换算为像素。
     * Reported by the Mixin at render time: caret position in GUI coordinates,
     * converted to pixels (guiScale) each tick for candidate window placement.
     *
     * @param guiX 光标 GUI X（组字预览插入点）
     * @param guiY 光标底部 GUI Y
     */
    public static void reportCaretPos(int guiX, int guiY) {
        INSTANCE.caretGuiX = guiX;
        INSTANCE.caretGuiY = guiY;
    }

    // ============================================================
    // 自绘组词窗（IMEOverlay）查询接口
    // Query surface for the self-drawn composition overlay
    // ============================================================

    /**
     * IME 当前是否处于激活状态（有聚焦文本框且已向本地层开启 IME）。
     * Whether the IME is currently enabled for the focused text field.
     *
     * @return true 表示 IME 已启用
     */
    public static boolean isImeActive() {
        return INSTANCE.lastImeEnabled;
    }

    /**
     * 当前候选页快照（本地层已按可见页切片）。
     * Current candidate page snapshot (pre-sliced by the native layer).
     *
     * @return 候选页；候选窗关闭或无候选时返回 null
     */
    public static CandidatePage getCandidatePage() {
        CandidatePage page = INSTANCE.candidatePage;
        return page != null && page.texts.length > 0 ? page : null;
    }

    /**
     * 自绘组词窗的锚点 X（GUI 逻辑坐标）：优先用 Mixin 上报的光标位置，
     * 尚未上报时回退到兼容目标（非 GuiTextField 体系，如 CatFrame EditBox）
     * 提供的锚点，再回退到聚焦文本框左下角。
     * Overlay anchor X in GUI coordinates: the reported caret position, falling
     * back to the active commit target's anchor (non-GuiTextField controls, e.g.
     * CatFrame EditBox), then to the focused field's bottom-left corner.
     *
     * @return 锚点 GUI X；无可用锚点时返回 0
     */
    public static int getOverlayAnchorX() {
        int x = INSTANCE.caretGuiX;
        if (x >= 0) {
            return x;
        }
        IMECommitTarget target = INSTANCE.activeTarget;
        if (target != null && target.getCaretX() >= 0) {
            return target.getCaretX();
        }
        GuiTextField field = focusedField.get();
        return field != null ? field.xPosition : 0;
    }

    /**
     * 自绘组词窗的锚点 Y（GUI 逻辑坐标，光标下沿）；回退同上。
     * Overlay anchor Y in GUI coordinates (below the caret); same fallback.
     *
     * @return 锚点 GUI Y；无可用锚点时返回 0
     */
    public static int getOverlayAnchorY() {
        int y = INSTANCE.caretGuiY;
        if (y >= 0) {
            return y;
        }
        IMECommitTarget target = INSTANCE.activeTarget;
        if (target != null && target.getCaretY() >= 0) {
            return target.getCaretY();
        }
        GuiTextField field = focusedField.get();
        return field != null ? field.yPosition + field.height + 2 : 0;
    }

    // ============================================================
    // GUI 键盘事件拦截（CatFrame 事件桥）
    // GUI keyboard interception (CatFrame event bridge)
    // ============================================================

    /**
     * 组字期间吞掉屏幕键盘事件，Linux 上额外承担按键→Rime 的转发：订阅
     * CatFrame 回填的 {@link CatFrameGuiEvent.KeyboardInputEvent.Pre}（发射自其
     * {@code MixinGuiScreenEventBridge}，包裹 {@code GuiScreen.handleKeyboardInput()}
     * 调用点）。
     * <p>
     * Linux/IBus·Fcitx 路径：IME 激活时把当前 LWJGL 键盘事件映射为 X keysym 同步转发
     * 给输入法引擎（{@link IMEBridge#processKey}），仅当引擎确实消费时才取消事件——
     * 未消费（英文直输、无关键、空闲 Escape）一律放行，不会出现 Esc 被吞、
     * 界面卡死的情况；未安装/未激活/非 Linux 后端时不碰事件。
     * <p>
     * 两平台公共兜底：组字进行中无条件取消——原版 {@code keyTyped}（含 Esc
     * 关屏）、CatFrame 拆分派发与全局按键派发均被压掉，避免组字中字母键被
     * 文本框二次录入、Esc 与输入法“取消组字”语义打架直接关屏等问题；
     * 组字结束后按键照常放行，不影响英文直输。
     * Swallows screen keyboard events while composing; on Linux additionally
     * forwards keys to the IME via the IBus/Fcitx backend, canceling the event
     * only when the IME actually consumed the key — unhandled keys (direct
     * English, idle Escape) always pass through, so the screen can never get stuck.
     *
     * @param event CatFrame 键盘事件（Pre，可取消）
     */
    @SubscribeEvent
    public void onGuiKeyboardPre(CatFrameGuiEvent.KeyboardInputEvent.Pre event) {
        if (forwardKeyToLinuxIme()) {
            event.setCanceled(true);
            return;
        }
        IMEBridge.CompositionInfo info = compositionInfo;
        if (info != null && info.isComposing) {
            event.setCanceled(true);
        }
    }

    /**
     * Linux/IBus·Fcitx 按键转发：把当前 LWJGL 键盘事件（含释放——Rime 靠 Shift
     * 单击释放切中英文）映射为 X keysym 后同步送入输入法。
     * Forwards the current LWJGL keyboard event (incl. releases — Rime toggles
     * ascii mode on a bare Shift release) to the IME as an X keysym.
     *
     * @return true 表示输入法已消费该键，调用方应取消事件
     */
    private boolean forwardKeyToLinuxIme() {
        if (!installed || !lastImeEnabled) {
            return false;
        }
        int backend = IMEBridge.getActiveBackend();
        if (backend != IMEBridge.BACKEND_IBUS && backend != IMEBridge.BACKEND_FCITX) {
            return false;
        }
        int key = Keyboard.getEventKey() & 0xFF;
        boolean press = Keyboard.getEventKeyState();
        int keysym;
        if (press) {
            keysym = keysymFromEvent(key, Keyboard.getEventCharacter());
            pressedKeysyms[key] = keysym;
        } else {
            // 释放：回放按下时记录的 keysym（释放事件通常不携带字符）
            // Release: replay the keysym remembered at press time
            keysym = pressedKeysyms[key];
            pressedKeysyms[key] = 0;
        }
        if (keysym == 0) {
            // 无法映射的键（F1…/功能键）直接放行 / Unmappable keys pass through
            return false;
        }
        int state = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                || keysym == 0xffe1 || keysym == 0xffe2) {
            state |= 1; // ShiftMask
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            state |= 1 << 2; // ControlMask
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            state |= 1 << 3; // Mod1Mask (Alt)
        }
        return IMEBridge.processKey(keysym, state, !press);
    }

    /**
     * LWJGL 键盘事件 → X11 keysym：特殊键查表（0xffXX），可打印 ASCII
     * 直接用码点（X11 Latin-1 keysym 与 ASCII 同值），其余返回 0 表示不转发。
     * LWJGL event -> X11 keysym: table for special keys, codepoint for
     * printable ASCII (Latin-1 keysyms equal ASCII), 0 = don't forward.
     *
     * @param key LWJGL 键码
     * @param ch  事件字符（按下时有效）
     * @return X keysym；0 表示无法映射
     */
    private static int keysymFromEvent(int key, char ch) {
        switch (key) {
            case Keyboard.KEY_RETURN:
                return 0xff0d;
            case Keyboard.KEY_NUMPADENTER:
                return 0xff8d;
            case Keyboard.KEY_BACK:
                return 0xff08;
            case Keyboard.KEY_TAB:
                return 0xff09;
            case Keyboard.KEY_ESCAPE:
                return 0xff1b;
            case Keyboard.KEY_DELETE:
                return 0xffff;
            case Keyboard.KEY_HOME:
                return 0xff50;
            case Keyboard.KEY_LEFT:
                return 0xff51;
            case Keyboard.KEY_UP:
                return 0xff52;
            case Keyboard.KEY_RIGHT:
                return 0xff53;
            case Keyboard.KEY_DOWN:
                return 0xff54;
            case Keyboard.KEY_PRIOR:
                return 0xff55;
            case Keyboard.KEY_NEXT:
                return 0xff56;
            case Keyboard.KEY_END:
                return 0xff57;
            case Keyboard.KEY_LSHIFT:
                return 0xffe1;
            case Keyboard.KEY_RSHIFT:
                return 0xffe2;
            case Keyboard.KEY_LCONTROL:
                return 0xffe3;
            case Keyboard.KEY_RCONTROL:
                return 0xffe4;
            case Keyboard.KEY_LMENU:
                return 0xffe9;
            case Keyboard.KEY_RMENU:
                return 0xffea;
            default:
                // 可打印 ASCII：keysym 即码点 / Printable ASCII: keysym == codepoint
                return ch >= 0x20 && ch < 0x7f ? ch : 0;
        }
    }

    // ============================================================
    // 客户端 tick：安装 / IME 开关 / 消费提交
    // ============================================================

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        ensureInstalled();
        if (!installed) {
            return;
        }

        // Linux 后端：泵送异步到达的信号（面板翻页、引擎异步更新等）；
        // 非 Linux 后端内部直接返回。/ Pump pending IBus/Fcitx signals; no-op elsewhere.
        IMEBridge.pump();

        Minecraft mc = Minecraft.getMinecraft();

        // 窗口重建自愈：仅在侦测到窗口改动（全屏切换/尺寸变化）时才复检句柄
        // Window-recreation self-healing: revalidate hwnd only on observed window
        // changes
        detectWindowChange(mc);

        GuiScreen screen = mc.currentScreen;
        GuiTextField field = focusedField.get();

        // 每 tick 查询激活的 IME 提交目标（NEI 搜索框/数量框、CatFrame EditBox 等）：
        // 非 GuiTextField 体系的控件靠它驱动 IME 开关与提交路由
        // Poll the active IME commit target each tick (NEI search/quantity box,
        // CatFrame EditBox, ...): non-GuiTextField controls rely on it for the
        // IME switch and commit routing
        IMECommitTarget target = IMEInputAPI.getActiveTarget();
        activeTarget = target;

        // 仅当处于某个 GUI 且有聚焦文本框（原生或兼容目标）时启用 IME；
        // 但"全屏治理型"目标（如 JourneyMap 全屏地图）可整体压制 IME：其
        // 隐藏聊天框的 vanilla 文本框永不失焦，默认焦点路径会误激活 IME，
        // 候选栏挡在地图上且组字吞掉 J 等快捷键（地图无法关闭）。
        // Enable the IME only when a GUI hosts a focused field (native or a
        // commit target); "fullscreen-governing" targets (e.g. the JourneyMap
        // fullscreen map) may suppress it entirely — their hidden chat widget's
        // vanilla text field never loses focus, which would spuriously enable
        // the IME and let the candidate bar cover the map while composition
        // swallows shortcut keys such as J (the map can no longer be closed).
        boolean wantEnable = screen != null && !IMEInputAPI.isImeBlocked()
                && ((field != null && field.isFocused()) || target != null);

        if (wantEnable != lastImeEnabled) {
            IMEBridge.setEnabled(hwnd, wantEnable);
            lastImeEnabled = wantEnable;
        }

        if (wantEnable) {
            updateCandidatePos(mc, screen, field);
            drainCommits(field);
        } else {
            // 未聚焦文本框时丢弃残留的提交，避免下次插入到错误位置
            commitQueue.clear();
            // 光标上报失效，回退到文本框坐标兼底 / Invalidate stale caret report
            caretGuiX = -1;
            caretGuiY = -1;
            // 候选页同步作废，避免自绘窗残留 / Drop stale candidate page
            candidatePage = null;
        }
    }

    /** 延迟安装：获取句柄、安装后端（TSF / IMM32 / IBus / Fcitx）、注册回调 */
    private void ensureInstalled() {
        if (installed) {
            return;
        }
        if (hwnd == 0L) {
            hwnd = resolveHwnd();
        }
        if (hwnd == 0L) {
            return;
        }
        IMEBridge.setCallback(this);
        // 安装前同步候选条归属：TSF 路径在安装时据此选择激活模式，
        // IBus 路径据此决定是否声明 CAP_LOOKUP_TABLE，Fcitx 路径据此
        // 决定是否声明 ClientSideInputPanel
        // Sync candidate bar ownership before install (TSF activation / IBus caps / Fcitx caps)
        IMEBridge.setNativeCandidateBar(nativeCandidateBar);
        int backend = IMEBridge.install(xDisplay, hwnd, backendMode);
        if (backend == IMEBridge.BACKEND_NONE) {
            // Linux：ibus/fcitx 连接失败（daemon 未运行/地址不可解析），后续 tick 重试，只警告一次
            // Linux: daemon connect failed (not running / no address); retry later, warn once
            if (!linuxInstallFailedLogged) {
                IMEInputBackport.logger.warn(
                        "[IME] Linux IME backend install failed (is ibus-daemon or fcitx5 running?), will retry");
                linuxInstallFailedLogged = true;
            }
            IMEBridge.clearCallback();
            return;
        }
        // 初始状态禁用 IME，待聚焦文本框时再启用
        IMEBridge.setEnabled(hwnd, false);
        lastImeEnabled = false;
        installed = true;
        // 初始化窗口改动侦测基准 / Seed the window-change detection baseline
        Minecraft mc = Minecraft.getMinecraft();
        lastFullscreen = mc.isFullScreen();
        lastDisplayW = mc.displayWidth;
        lastDisplayH = mc.displayHeight;
        logBackendResult(backend, "Installed");
    }

    /**
     * 输出后端安装结果日志；若 AUTO/TSF 模式最终落在 IMM32，明确提示发生了降级。
     * Logs the backend install result; explicitly notes the downgrade when an
     * AUTO/TSF request ended up on the IMM32 path.
     *
     * @param backend 实际激活的后端
     * @param action  日志动词（Installed / Re-installed）
     */
    private void logBackendResult(int backend, String action) {
        String name = IMEBridge.backendName(backend);
        if (backend == IMEBridge.BACKEND_IMM32 && backendMode != IMEBridge.BACKEND_IMM32) {
            IMEInputBackport.logger.info("[IME] " + action + " backend=" + name
                    + " (TSF unavailable, fell back from "
                    + IMEBridge.backendName(backendMode) + ") hwnd=" + hwnd);
        } else {
            IMEInputBackport.logger.info("[IME] " + action + " backend=" + name + " hwnd=" + hwnd);
        }
    }

    /**
     * 窗口改动侦测：全屏标志或窗口尺寸变化时才触发一次句柄复检，
     * 平时只是三个字段比对，零分配零反射。普通拉窗口/最大化也会触发，
     * 但句柄没变，复检就是一次空比对；只有真重建（LWJGL2 切换全屏/
     * 无边框）才走重挂。
     * Window-change detection: only a fullscreen-flag or display-size change
     * triggers an hwnd revalidation; the steady state is three field compares
     * with zero allocation and zero reflection. Plain resizes/maximize also
     * trigger it, but the handle is unchanged so the recheck is a no-op; only a
     * real recreation (LWJGL2 fullscreen/borderless switch) leads to a rehook.
     */
    private void detectWindowChange(Minecraft mc) {
        boolean fs = mc.isFullScreen();
        if (fs != lastFullscreen || mc.displayWidth != lastDisplayW || mc.displayHeight != lastDisplayH) {
            lastFullscreen = fs;
            lastDisplayW = mc.displayWidth;
            lastDisplayH = mc.displayHeight;
            rehookPending = true;
        }
        if (rehookPending && rehookWindow()) {
            rehookPending = false;
        }
    }

    /**
     * 句柄复检与重挂：解析当前句柄，与已安装句柄不一致时重新安装后端：
     * 卸载旧句柄上的后端（清空 native 侧状态，对已销毁的窗口无害）→
     * 在新句柄上按配置模式重新安装（TSF 重挂 AssociateFocus 同样走这里）→
     * 重置 IME 使能状态强制下个 tick 重新同步 → 作废残留快照。
     * 若不处理，旧后端随窗口一起死掉，组字/提交/候选回调全断直到重启游戏。
     * Hwnd revalidation and rehook: resolves the current handle and re-installs
     * the backend when it differs from the installed one. Uninstall on the old
     * handle (clears native state, harmless on a destroyed window) → re-install
     * per the configured mode (the TSF AssociateFocus re-attach takes the same
     * path) → reset the IME-enabled state so the next tick resyncs → drop stale
     * snapshots. Without this, all IME callbacks stay dead until the game
     * restarts.
     *
     * @return true 表示复检完成；false 表示切换中途句柄暂不可用，需下个 tick 重试
     *         true when done; false when the handle is momentarily unavailable
     *         mid-switch and the caller should retry next tick
     */
    private boolean rehookWindow() {
        long current = resolveHwnd();
        if (current == 0L) {
            return false;
        }
        if (current == hwnd) {
            // 窗口没重建（普通缩放/最大化），无需重挂 / Not recreated, nothing to do
            return true;
        }
        IMEBridge.uninstall(hwnd);
        int backend = IMEBridge.install(xDisplay, current, backendMode);
        IMEBridge.setEnabled(current, false);
        IMEInputBackport.logger.info("[IME] Game window recreated, rehooked hwnd "
                + hwnd + " -> " + current);
        hwnd = current;
        logBackendResult(backend, "Re-installed");
        lastImeEnabled = false;
        // 旧窗口的组字/候选快照已无意义 / Stale snapshots from the old window
        compositionInfo = null;
        candidatePage = null;
        return true;
    }

    /**
     * 消费已提交文字：默认写入聚焦文本框；有激活的 IME 提交目标（NEI 搜索框/
     * 数量框等，见 {@link IMECommitTarget}）时交给目标自身的提交管线——NEI 场景
     * 即走按键注入（对齐 IngameIME 的 NEI 集成），由 LayoutManager 路由并触发
     * onTextChange 刷新搜索结果，直接 writeText 会绕过刷新；CatFrame EditBox 等
     * 非 GuiTextField 控件由目标实现自行插入并触发自身刷新。
     * Drains committed text: writes into the focused field by default; when an
     * IME commit target is active (NEI search/quantity box, see
     * {@link IMECommitTarget}) the text goes through the target's own pipeline —
     * for NEI that is key injection (mirroring IngameIME) so LayoutManager routes
     * it and onTextChange refreshes the results (a plain writeText would skip the
     * refresh); for non-GuiTextField controls (CatFrame EditBox) the target
     * inserts and refreshes itself.
     */
    private void drainCommits(GuiTextField field) {
        String text;
        IMECommitTarget target = activeTarget;
        while ((text = commitQueue.poll()) != null) {
            if (text.length() == 0) {
                continue;
            }
            if (target != null) {
                target.commitText(text);
            } else if (field != null && field.isFocused()) {
                field.writeText(text);
            }
        }
    }

    /**
     * 计算并同步系统组词/候选窗位置（GUI 坐标→窗口客户区像素坐标）。
     * 优先使用 Mixin 上报的光标位置（跟随光标，对齐准绳的 setTextInputArea 语义），
     * 尚未上报时回退到文本框左下角。
     * 组字中额外下压一个 PreEdit 框高：自绘 PreEdit 框占据光标下沿，
     * 系统组词窗贴到其下方，两框上下相接、一体呈现（仿 Windows 原生组词 UI）。
     * Prefers the caret position reported by the Mixin (caret following, matching
     * the modern setTextInputArea semantics); falls back to the field's corner.
     * While composing, the OS window is pushed down by one PreEdit box height so
     * it stacks right below the self-drawn PreEdit box, like the native IME UI.
     */
    private void updateCandidatePos(Minecraft mc, GuiScreen screen, GuiTextField field) {
        if (screen.width <= 0 || screen.height <= 0) {
            return;
        }
        double scaleX = (double) mc.displayWidth / (double) screen.width;
        double scaleY = (double) mc.displayHeight / (double) screen.height;
        int guiX = caretGuiX;
        int guiY = caretGuiY;
        if (guiX < 0 || guiY < 0) {
            // 兼容目标（非 GuiTextField 体系）提供的光标锚点
            // Caret anchor supplied by the active commit target
            IMECommitTarget target = activeTarget;
            if (target != null && target.getCaretX() >= 0 && target.getCaretY() >= 0) {
                guiX = target.getCaretX();
                guiY = target.getCaretY();
            } else if (field != null) {
                // 回退：文本框左下角（GUI 单位） / Fallback: field bottom-left corner
                guiX = field.xPosition;
                guiY = field.yPosition + field.height;
            } else {
                // 无锚点：原生文本框与兼容目标均未提供光标位置，跳过定位
                // No anchor: neither the native field nor the target has a caret
                return;
            }
        }
        // 组字中：系统窗让位给自绘 PreEdit 框，压到其下沿（+1 为两框间隙）
        // Composing: yield to the self-drawn PreEdit box, stack right below it
        if (getCompositionInfo() != null) {
            guiY += IMEOverlay.getPreeditBoxHeight(mc.fontRenderer) + 1;
        }
        int px = (int) Math.round(guiX * scaleX);
        int py = (int) Math.round(guiY * scaleY);
        IMEBridge.setCaretPos(hwnd, px, py);
    }

    // ============================================================
    // 句柄解析
    // ============================================================

    /**
     * 反射获取 LWJGL 游戏窗口句柄（按平台分支）。
     * Windows：{@code org.lwjgl.opengl.WindowsDisplay.current_display}（static）
     * → {@code hwnd}（private long）；
     * Linux：{@code org.lwjgl.opengl.LinuxDisplay.getDisplay()/getWindow()}
     * （package-private static long），Display 指针顺带存入 {@link #xDisplay}。
     * Resolves the LWJGL window handle per platform; on Linux the X Display
     * pointer is captured into {@link #xDisplay} as a side effect.
     *
     * @return 窗口句柄（HWND / X Window），失败返回 0
     */
    private long resolveHwnd() {
        try {
            if (IMEBridge.isLinux()) {
                Class<?> ld = Class.forName("org.lwjgl.opengl.LinuxDisplay");
                java.lang.reflect.Method getDisplay = ld.getDeclaredMethod("getDisplay");
                getDisplay.setAccessible(true);
                java.lang.reflect.Method getWindow = ld.getDeclaredMethod("getWindow");
                getWindow.setAccessible(true);
                long display = ((Long) getDisplay.invoke(null)).longValue();
                long window = ((Long) getWindow.invoke(null)).longValue();
                if (display == 0L || window == 0L) {
                    return 0L;
                }
                xDisplay = display;
                return window;
            }
            Class<?> wd = Class.forName("org.lwjgl.opengl.WindowsDisplay");
            Field curField = wd.getDeclaredField("current_display");
            curField.setAccessible(true);
            Object display = curField.get(null);
            if (display == null) {
                return 0L;
            }
            Field hwndField = wd.getDeclaredField("hwnd");
            hwndField.setAccessible(true);
            return hwndField.getLong(display);
        } catch (Throwable t) {
            if (!handleLookupFailedLogged) {
                IMEInputBackport.logger.warn("[IME] Failed to resolve LWJGL window handle: " + t);
                handleLookupFailedLogged = true;
            }
            return 0L;
        }
    }

    // ============================================================
    // IMECallback（本地层回调）
    // ============================================================

    @Override
    public void onCompositionUpdate(IMEBridge.CompositionInfo info) {
        this.compositionInfo = info;
        // 组字结束时候选页一并作废（个别输入法不发 IMN_CLOSECANDIDATE）
        // Drop the candidate page when composition ends (some IMEs skip
        // IMN_CLOSECANDIDATE)
        if (info == null || !info.isComposing) {
            this.candidatePage = null;
        }
    }

    @Override
    public void onCandidatesUpdate(IMEBridge.Candidate[] candidates, int selectedIndex) {
        // 候选框归属由配置决定：原生候选条（默认）时快照仅作旁路保留；
        // 自绘模式（candidateNativeBar=false）下快照是 IMEOverlay 候选框的唯一数据源。
        // Candidate bar ownership is config-driven: with the native bar (default)
        // this snapshot is a mere side channel; in self-draw mode
        // (candidateNativeBar=false) it is the sole feed of the IMEOverlay box.
        if (candidates == null || candidates.length == 0) {
            this.candidatePage = null;
            return;
        }
        String[] texts = new String[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            texts[i] = candidates[i] != null && candidates[i].text != null ? candidates[i].text : "";
        }
        this.candidatePage = new CandidatePage(texts, selectedIndex);
    }

    @Override
    public void onCommit(String text) {
        if (text != null && text.length() > 0) {
            commitQueue.offer(text);
        }
    }
}
