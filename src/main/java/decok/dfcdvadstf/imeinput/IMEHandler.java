package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import decok.dfcdvadstf.catframe.ui.overlay.OverlayManager;
import decok.dfcdvadstf.imeinput.gui.IMEOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.jnilib.imeinput.IMEBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * IME 输入法客户端管理器。
 * <p>
 * 职责：
 * <ol>
 *   <li>获取 LWJGL 游戏窗口句柄（反射 {@code WindowsDisplay.current_display.hwnd}）并子类化</li>
 *   <li>作为 {@link IMEBridge.IMECallback} 接收组字/提交/候选回调</li>
 *   <li>在客户端 tick 中：按聚焦文本框开关 IME、定位候选窗、消费已提交文字</li>
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

    /** 已解析到的窗口句柄，0 表示尚未获取 */
    private long hwnd = 0L;
    /** 是否已完成子类化与回调注册 */
    private boolean installed = false;
    /** 上一次向本地层同步的 IME 使能状态 */
    private boolean lastImeEnabled = false;
    /** 是否已尝试过（失败）获取句柄，避免每 tick 刷屏日志 */
    private boolean handleLookupFailedLogged = false;

    private IMEHandler() {
    }

    // ============================================================
    //  数据结构
    // ============================================================

    /**
     * 候选页快照：本地层已按 {@code dwPageStart}/{@code dwPageSize} 切好的当前可见页。
     * 不可变，回调线程构造后整体发布，渲染线程只读。
     * Candidate page snapshot: the visible page pre-sliced by the native layer.
     * Immutable — published as a whole from the callback thread, read-only for rendering.
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
    //  生命周期
    // ============================================================

    /**
     * 在客户端初始化时调用：注册 tick 事件监听，并把自绘组词窗 Overlay
     * 挂入 CatFrame 的 OverlayManager（渲染由 CatFrame 的 DrawScreenEvent 驱动）。
     * 真正的窗口子类化延迟到第一次 tick（此时 Display 已创建）。
     * Registers tick listener and hooks the self-drawn composition overlay into
     * CatFrame's OverlayManager (rendering driven by CatFrame's DrawScreenEvent bridge).
     */
    public static void init() {
        FMLCommonHandler.instance().bus().register(INSTANCE);
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
     * Current composition snapshot (blocks + focused block + caret) for inline rendering.
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
    //  自绘组词窗（IMEOverlay）查询接口
    //  Query surface for the self-drawn composition overlay
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
     * 尚未上报时回退到聚焦文本框左下角。
     * Overlay anchor X in GUI coordinates: the reported caret position,
     * falling back to the focused field's bottom-left corner.
     *
     * @return 锚点 GUI X；无可用锚点时返回 0
     */
    public static int getOverlayAnchorX() {
        int x = INSTANCE.caretGuiX;
        if (x >= 0) {
            return x;
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
        GuiTextField field = focusedField.get();
        return field != null ? field.yPosition + field.height + 2 : 0;
    }

    // ============================================================
    //  客户端 tick：安装 / IME 开关 / 消费提交
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

        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        GuiTextField field = focusedField.get();

        // 仅当处于某个 GUI 且有聚焦文本框时启用 IME
        boolean wantEnable = screen != null && field != null && field.isFocused();

        if (wantEnable != lastImeEnabled) {
            IMEBridge.setImeEnabled(hwnd, wantEnable);
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

    /** 延迟安装：获取句柄、子类化、注册回调 */
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
        IMEBridge.subclassWindow(hwnd);
        // 初始状态禁用 IME，待聚焦文本框时再启用
        IMEBridge.setImeEnabled(hwnd, false);
        lastImeEnabled = false;
        installed = true;
        IMEInoutBackport.logger.info("[IME] Subclassed game window hwnd=" + hwnd);
    }

    /** 消费已提交文字，写入聚焦文本框 */
    private void drainCommits(GuiTextField field) {
        String text;
        while ((text = commitQueue.poll()) != null) {
            if (field != null && field.isFocused() && text.length() > 0) {
                field.writeText(text);
            }
        }
    }

    /**
     * 计算并同步候选窗位置（GUI 坐标→窗口客户区像素坐标）。
     * 优先使用 Mixin 上报的光标位置（跟随光标，对齐准绳的 setTextInputArea 语义），
     * 尚未上报时回退到文本框左下角。
     * Prefers the caret position reported by the Mixin (caret following, matching
     * the modern setTextInputArea semantics); falls back to the field's corner.
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
            // 回退：文本框左下角（GUI 单位） / Fallback: field bottom-left corner
            guiX = field.xPosition;
            guiY = field.yPosition + field.height;
        }
        int px = (int) Math.round(guiX * scaleX);
        int py = (int) Math.round(guiY * scaleY);
        IMEBridge.setCandidatePos(hwnd, px, py);
    }

    // ============================================================
    //  句柄解析
    // ============================================================

    /**
     * 反射获取 LWJGL 游戏窗口句柄。
     * {@code org.lwjgl.opengl.WindowsDisplay.current_display}（static）→ {@code hwnd}（private long）。
     *
     * @return 窗口句柄，失败返回 0
     */
    private long resolveHwnd() {
        try {
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
                IMEInoutBackport.logger.warn("[IME] Failed to resolve LWJGL window handle: " + t);
                handleLookupFailedLogged = true;
            }
            return 0L;
        }
    }

    // ============================================================
    //  IMECallback（本地层回调）
    // ============================================================

    @Override
    public void onCompositionUpdate(IMEBridge.CompositionInfo info) {
        this.compositionInfo = info;
        // 组字结束时候选页一并作废（个别输入法不发 IMN_CLOSECANDIDATE）
        // Drop the candidate page when composition ends (some IMEs skip IMN_CLOSECANDIDATE)
        if (info == null || !info.isComposing) {
            this.candidatePage = null;
        }
    }

    @Override
    public void onCandidatesUpdate(IMEBridge.Candidate[] candidates, int selectedIndex) {
        // 窗口模式保留系统原生候选窗；快照供独占全屏下的自绘组词窗（IMEOverlay）消费。
        // Windowed mode keeps the OS candidate window; this snapshot feeds the
        // self-drawn composition overlay (IMEOverlay) in exclusive fullscreen.
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
