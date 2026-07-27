package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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

    /** 当前组字预览串（用于文本框下划线展示） */
    private volatile String compositionText = "";
    /** 是否正在组字 */
    private volatile boolean composing = false;

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
    //  生命周期
    // ============================================================

    /**
     * 在客户端初始化时调用：注册 tick 事件监听。
     * 真正的窗口子类化延迟到第一次 tick（此时 Display 已创建）。
     */
    public static void init() {
        FMLCommonHandler.instance().bus().register(INSTANCE);
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
        return INSTANCE.composing ? INSTANCE.compositionText : "";
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

    /** 计算并同步候选窗位置（GUI 坐标→窗口客户区像素坐标） */
    private void updateCandidatePos(Minecraft mc, GuiScreen screen, GuiTextField field) {
        if (screen.width <= 0 || screen.height <= 0) {
            return;
        }
        double scaleX = (double) mc.displayWidth / (double) screen.width;
        double scaleY = (double) mc.displayHeight / (double) screen.height;
        // 光标近似位置：文本框左下角（GUI 单位），足够让候选窗贴近输入位置
        int px = (int) Math.round(field.xPosition * scaleX);
        int py = (int) Math.round((field.yPosition + field.height) * scaleY);
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
        this.compositionText = info.composition == null ? "" : info.composition;
        this.composing = info.isComposing;
    }

    @Override
    public void onCandidatesUpdate(IMEBridge.Candidate[] candidates) {
        // 采用系统原生候选窗，暂不自绘；此处保留以便后续切换为自绘模式。
    }

    @Override
    public void onCommit(String text) {
        if (text != null && text.length() > 0) {
            commitQueue.offer(text);
        }
    }
}
