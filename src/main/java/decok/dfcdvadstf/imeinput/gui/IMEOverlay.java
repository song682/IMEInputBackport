package decok.dfcdvadstf.imeinput.gui;

import decok.dfcdvadstf.catframe.ui.components.AbstractComponent;
import decok.dfcdvadstf.catframe.ui.overlay.Overlay;
import decok.dfcdvadstf.catframe.ui.overlay.OverlayContext;
import decok.dfcdvadstf.catframe.ui.overlay.ScreenAnchor;
import decok.dfcdvadstf.imeinput.IMEHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import org.jnilib.imeinput.IMEBridge;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * IME 自绘组词窗 Overlay（接入 CatFrame Overlay 体系）。
 * <p>
 * 仿 Windows 原生组词框的一体式结构，PreEdit 框与候选行作为同一浮窗上下两行一起渲染：
 * <pre>
 * ┌──────────────────────────┐
 * │ 组字串（分段 + 聚焦段 + 光标）│  ← PreEdit 行
 * │ 1. 候选  2. 候选  3. 候选   │  ← 候选行（选中项高亮）
 * └──────────────────────────┘
 * </pre>
 * 仅在<b>独占全屏</b>时显示——窗口模式下系统原生候选窗可正常弹出，自绘反而会双重显示；
 * 独占全屏下系统窗无法覆盖游戏画面，本 Overlay 即为其替代品。
 * <p>
 * IME self-drawn composition window overlay (plugged into the CatFrame Overlay system).
 * Mirrors the native Windows composition window: the PreEdit widget and the candidate
 * row render together as one floating box. Shown only in <b>exclusive fullscreen</b>,
 * where the OS candidate window cannot appear on top of the game; in windowed mode the
 * native window works fine and self-drawing would duplicate it.
 * <p>
 * 渲染驱动完全复用 CatFrame 的 {@code ClientOverlayHandler}
 * （{@code DrawScreenEvent.Post} → {@code OverlayManager.renderAll}），
 * 本类只负责注册与绘制，无需任何 Mixin。
 * Rendering is driven entirely by CatFrame's {@code ClientOverlayHandler}; this class
 * only registers itself and draws — no extra Mixin required.
 */
public final class IMEOverlay extends AbstractComponent implements Overlay {

    /** 单例：由 {@link IMEHandler#init()} 注册到 OverlayManager / Singleton, registered once */
    public static final IMEOverlay INSTANCE = new IMEOverlay();

    // ──── 视觉常量 / Visual constants ────

    /** 浮窗内边距 / Box padding */
    private static final int PADDING = 3;
    /** PreEdit 行与候选行之间的间距 / Gap between the preedit and candidate rows */
    private static final int ROW_GAP = 3;
    /** 候选项之间的间距 / Gap between candidate items */
    private static final int ITEM_GAP = 6;
    /** 浮窗背景色（深色半透明）/ Box background (translucent dark) */
    private static final int COLOR_BG = 0xE8101010;
    /** 浮窗边框色 / Box border */
    private static final int COLOR_BORDER = 0xFF3C3C3C;
    /** 普通文字色 / Normal text */
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    /** 聚焦分段文字色 / Focused clause text */
    private static final int COLOR_FOCUSED = 0xFFFFFFFF;
    /** 组字内光标竖线色 / In-composition caret bar */
    private static final int COLOR_CARET = 0xFFD0D0D0;
    /** 选中候选项背景色（取自 IngameIME 的用色）/ Selected candidate background */
    private static final int COLOR_SELECTED_BG = 0xEBB2DAE0;
    /** 选中候选项文字色（浅底深字）/ Selected candidate text (dark on light) */
    private static final int COLOR_SELECTED_TEXT = 0xFF202020;

    private IMEOverlay() {
    }

    // ──── Overlay 契约 / Overlay contract ────

    /** 文本框只存在于打开的 GUI 上，故仅走 SCREEN 渲染路径 / Text fields only live on screens */
    @Override
    public OverlayContext getContext() {
        return OverlayContext.SCREEN;
    }

    /**
     * TOP_LEFT 锚点的 {@code resolveX/Y} 直接返回 offset，
     * 正好把 offset 当自由坐标用，实现光标跟随定位。
     * TOP_LEFT resolves to the raw offset, so the offset doubles as a free
     * coordinate — giving us caret-following placement.
     */
    @Override
    public ScreenAnchor getAnchor() {
        return ScreenAnchor.TOP_LEFT;
    }

    /** 浮窗 X：光标 GUI 坐标，右缘越界时向左收 / Caret X, clamped to the right screen edge */
    @Override
    public int getOffsetX() {
        int x = IMEHandler.getOverlayAnchorX();
        ScaledResolution sr = currentResolution();
        if (x + this.width > sr.getScaledWidth()) {
            x = sr.getScaledWidth() - this.width;
        }
        return Math.max(0, x);
    }

    /** 浮窗 Y：光标下沿，下缘越界时翻转到光标上方 / Below the caret, flipped above when clipped */
    @Override
    public int getOffsetY() {
        int y = IMEHandler.getOverlayAnchorY();
        ScaledResolution sr = currentResolution();
        if (y + this.height > sr.getScaledHeight()) {
            // 翻到锚点上方：锚点 Y 减去一行文本高与自身高 / Flip above the anchor point
            y = IMEHandler.getOverlayAnchorY() - Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT
                    - 4 - this.height;
        }
        return Math.max(0, y);
    }

    /**
     * 显示门控：独占全屏 + IME 激活 + 有组字或候选内容。
     * 通过时顺带重算浮窗尺寸——OverlayManager 在本调用之后立刻用
     * {@code getWidth()/getHeight()} 解析锚点，此处是唯一先于取值的稳定钩子。
     * Visibility gate: exclusive fullscreen + IME active + has preedit/candidates.
     * Also relayouts here, since OverlayManager reads width/height right after
     * this call — the only stable hook that runs before those getters.
     */
    @Override
    public boolean isVisible() {
        if (!Display.isFullscreen() || !IMEHandler.isImeActive()) {
            return false;
        }
        IMEBridge.CompositionInfo info = IMEHandler.getCompositionInfo();
        IMEHandler.CandidatePage page = IMEHandler.getCandidatePage();
        if (info == null && page == null) {
            return false;
        }
        relayout(Minecraft.getMinecraft().fontRenderer, info, page);
        return true;
    }

    // ──── 布局 / Layout ────

    /**
     * 依据当前组字与候选快照重算浮窗尺寸。
     * Recomputes the box size from the current preedit/candidate snapshots.
     */
    private void relayout(FontRenderer fr, IMEBridge.CompositionInfo info, IMEHandler.CandidatePage page) {
        int preeditW = 0;
        int candW = 0;
        int h = PADDING * 2;

        if (info != null) {
            for (int i = 0; i < info.blocks.length; i++) {
                preeditW += fr.getStringWidth(info.blocks[i]);
                if (i < info.blocks.length - 1) {
                    preeditW += 1; // 分段间 1px 缝隙 / 1px inter-block gap
                }
            }
            h += fr.FONT_HEIGHT + 2; // 文本 + 下划线 / text + underline
        }

        if (page != null) {
            for (int i = 0; i < page.texts.length; i++) {
                candW += fr.getStringWidth(candidateLabel(i, page.texts[i]));
                if (i < page.texts.length - 1) {
                    candW += ITEM_GAP;
                }
            }
            if (info != null) {
                h += ROW_GAP;
            }
            h += fr.FONT_HEIGHT;
        }

        this.width = Math.max(preeditW, candW) + PADDING * 2;
        this.height = h;
    }

    /** 候选项显示文本："序号. 候选词" / Candidate display label: "index. text" */
    private static String candidateLabel(int index, String text) {
        return (index + 1) + ". " + text;
    }

    private static ScaledResolution currentResolution() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    // ──── 渲染 / Rendering ────

    /**
     * 绘制一体式组词浮窗：背景与边框 → PreEdit 行（分段高亮 + 光标）→ 候选行（选中项高亮）。
     * 坐标已由 OverlayManager 依锚点与 offset 解析并临时写入 x/y。
     * Draws the unified composition box: background/border → preedit row
     * (block highlight + caret) → candidate row (selection highlight).
     * Position was already resolved and assigned by OverlayManager.
     */
    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        IMEBridge.CompositionInfo info = IMEHandler.getCompositionInfo();
        IMEHandler.CandidatePage page = IMEHandler.getCandidatePage();
        if (info == null && page == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRenderer;
        int x = this.x;
        int y = this.y;

        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // 背景 + 1px 边框 / Background + 1px border
        Gui.drawRect(x - 1, y - 1, x + this.width + 1, y + this.height + 1, COLOR_BORDER);
        Gui.drawRect(x, y, x + this.width, y + this.height, COLOR_BG);

        int cy = y + PADDING;

        // ── PreEdit 行：逐分段绘制，聚焦段亮色 + 加粗下划线，组字内光标竖线 ──
        // ── Preedit row: per-block, focused block bright + thick underline, caret bar ──
        if (info != null) {
            int bx = x + PADDING;
            int underlineY = cy + fr.FONT_HEIGHT;
            int charOffset = 0;
            int caretX = bx;
            for (int i = 0; i < info.blocks.length; i++) {
                String block = info.blocks[i];
                if (block.isEmpty()) {
                    continue;
                }
                int caretInBlock = info.caretPosition - charOffset;
                if (caretInBlock >= 0 && caretInBlock <= block.length()) {
                    caretX = bx + fr.getStringWidth(block.substring(0, caretInBlock));
                }
                boolean focused = i == info.focusedBlock;
                fr.drawStringWithShadow(block, bx, cy, focused ? COLOR_FOCUSED : COLOR_TEXT);
                int bw = fr.getStringWidth(block);
                Gui.drawRect(bx, underlineY, bx + bw, underlineY + (focused ? 2 : 1), 0xFFFFFFFF);
                bx += bw + (i < info.blocks.length - 1 ? 1 : 0);
                charOffset += block.length();
            }
            Gui.drawRect(caretX, cy - 1, caretX + 1, cy + fr.FONT_HEIGHT, COLOR_CARET);
            cy += fr.FONT_HEIGHT + 2 + ROW_GAP;
        }

        // ── 候选行：横向排布，选中项浅底深字高亮 ──
        // ── Candidate row: horizontal items, selection drawn dark-on-light ──
        if (page != null) {
            int px = x + PADDING;
            for (int i = 0; i < page.texts.length; i++) {
                String label = candidateLabel(i, page.texts[i]);
                int lw = fr.getStringWidth(label);
                if (i == page.selectedIndex) {
                    Gui.drawRect(px - 1, cy - 1, px + lw + 1, cy + fr.FONT_HEIGHT, COLOR_SELECTED_BG);
                    fr.drawString(label, px, cy, COLOR_SELECTED_TEXT);
                } else {
                    fr.drawStringWithShadow(label, px, cy, COLOR_TEXT);
                }
                px += lw + ITEM_GAP;
            }
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }
}
