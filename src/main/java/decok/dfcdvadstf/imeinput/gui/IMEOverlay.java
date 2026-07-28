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
 * 仿 Windows 原生组词窗的两框堆叠结构，PreEdit 框与组词候选框上下相接、一起呈现：
 * <pre>
 * ┌──────────────────────────┐
 * │ 组字串（分段 + 聚焦段 + 光标）│  ← PreEdit 框（任何模式都自绘）
 * ├──────────────────────────┤
 * │ 1. 候选  2. 候选  3. 候选   │  ← 组词候选框（Windows 的那个框）
 * └──────────────────────────┘
 * </pre>
 * 两个框在任何显示模式下都要一起出现，区别只在候选框由谁来画：
 * <ul>
 *   <li><b>窗口化</b>：候选框就是系统原生组词窗——{@link IMEHandler} 把系统窗
 *       下压到 PreEdit 框下沿（CFS_POINT），两框上下相接；自绘候选会双重显示，故不画。</li>
 *   <li><b>独占全屏</b>：系统窗无法覆盖游戏画面，候选框改由本 Overlay 自绘
 *       （native 已按可见页切片，选中项高亮），与 PreEdit 框一起渲染。</li>
 * </ul>
 * IME self-drawn composition overlay (plugged into the CatFrame Overlay system).
 * Mirrors the native Windows composition UI as two stacked boxes: the <b>PreEdit
 * box</b> is always self-drawn while composing, in windowed and fullscreen mode alike.
 * The <b>candidate box</b> below it is the OS composition window in windowed mode
 * (pushed right below the PreEdit box by {@link IMEHandler}), and is self-drawn only
 * in <b>exclusive fullscreen</b>, where the OS window cannot appear on top of the game.
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

    /** 框体内边距 / Box padding */
    private static final int PADDING = 3;
    /** PreEdit 框与候选框之间的间隙（两框相接）/ Gap between the two stacked boxes */
    private static final int BOX_GAP = 1;
    /** 候选项之间的间距 / Gap between candidate items */
    private static final int ITEM_GAP = 6;
    /** 框体背景色（深色半透明）/ Box background (translucent dark) */
    private static final int COLOR_BG = 0xE8101010;
    /** 框体边框色 / Box border */
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

    // ──── 布局缓存（isVisible 中重算，render 中消费）────
    // ──── Layout cache (recomputed in isVisible, consumed by render) ────

    /** PreEdit 框宽（含边框；0 表示本帧无此框）/ PreEdit box width incl. border */
    private int preeditBoxW;
    /** PreEdit 框高（含边框）/ PreEdit box height incl. border */
    private int preeditBoxH;
    /** 候选框宽（含边框；0 表示本帧无此框）/ Candidate box width incl. border */
    private int candBoxW;
    /** 候选框高（含边框）/ Candidate box height incl. border */
    private int candBoxH;

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
     * 显示门控：IME 激活 + 有组字（任何模式）或有候选（仅独占全屏）。
     * 通过时顺带重算浮窗尺寸——OverlayManager 在本调用之后立刻用
     * {@code getWidth()/getHeight()} 解析锚点，此处是唯一先于取值的稳定钩子。
     * Visibility gate: IME active + composing (any display mode) or has
     * candidates (exclusive fullscreen only).
     * Also relayouts here, since OverlayManager reads width/height right after
     * this call — the only stable hook that runs before those getters.
     */
    @Override
    public boolean isVisible() {
        if (!IMEHandler.isImeActive()) {
            return false;
        }
        IMEBridge.CompositionInfo info = IMEHandler.getCompositionInfo();
        IMEHandler.CandidatePage page = visibleCandidatePage();
        if (info == null && page == null) {
            return false;
        }
        relayout(Minecraft.getMinecraft().fontRenderer, info, page);
        return true;
    }

    /**
     * 当前应自绘的候选页：仅独占全屏时返回快照，窗口化下返回 null
     * （系统候选窗自行弹出，自绘会双重显示）。
     * The candidate page to self-draw: only in exclusive fullscreen; in windowed
     * mode the OS candidate window shows itself, so we return null here.
     */
    private static IMEHandler.CandidatePage visibleCandidatePage() {
        return Display.isFullscreen() ? IMEHandler.getCandidatePage() : null;
    }

    // ──── 布局 / Layout ────

    /**
     * 依据当前组字与候选快照重算两个框的尺寸与总包围盒。
     * Recomputes both box sizes and the stacked bounding box from the snapshots.
     */
    private void relayout(FontRenderer fr, IMEBridge.CompositionInfo info, IMEHandler.CandidatePage page) {
        preeditBoxW = 0;
        preeditBoxH = 0;
        candBoxW = 0;
        candBoxH = 0;

        if (info != null) {
            int w = 0;
            for (int i = 0; i < info.blocks.length; i++) {
                w += fr.getStringWidth(info.blocks[i]);
                if (i < info.blocks.length - 1) {
                    w += 1; // 分段间 1px 缝隙 / 1px inter-block gap
                }
            }
            preeditBoxW = w + PADDING * 2 + 2; // +2 边框 / border
            preeditBoxH = getPreeditBoxHeight(fr);
        }

        if (page != null) {
            int w = 0;
            for (int i = 0; i < page.texts.length; i++) {
                w += fr.getStringWidth(candidateLabel(i, page.texts[i]));
                if (i < page.texts.length - 1) {
                    w += ITEM_GAP;
                }
            }
            candBoxW = w + PADDING * 2 + 2;
            candBoxH = fr.FONT_HEIGHT + PADDING * 2 + 2;
        }

        // 包围盒：两框上下堆叠（供 OverlayManager 解析锚点与越界钳位）
        // Bounding box of the stacked boxes (for anchoring and edge clamping)
        this.width = Math.max(preeditBoxW, candBoxW);
        this.height = preeditBoxH + candBoxH
                + (preeditBoxH > 0 && candBoxH > 0 ? BOX_GAP : 0);
    }

    /**
     * PreEdit 框总高（GUI 单位，含内边距与边框）。供 {@link IMEHandler}
     * 把系统组词窗下压到本框下沿，使窗口化下两框上下相接。
     * Total PreEdit box height (GUI units, padding + border included). Used by
     * {@link IMEHandler} to push the OS composition window right below this box.
     *
     * @param fr 字体渲染器 / font renderer
     * @return 框高（GUI 单位）/ box height in GUI units
     */
    public static int getPreeditBoxHeight(FontRenderer fr) {
        // 文本 + 下划线 + 内边距 + 边框 / text + underline + padding + border
        return fr.FONT_HEIGHT + 2 + PADDING * 2 + 2;
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
     * 绘制两框堆叠的组词窗：PreEdit 框（分段高亮 + 光标）在上，
     * 候选框（仅独占全屏，选中项高亮）紧贴其下，各自带边框与背景。
     * 坐标已由 OverlayManager 依锚点与 offset 解析并临时写入 x/y。
     * Draws the two stacked boxes: PreEdit box (block highlight + caret) on top,
     * candidate box (fullscreen only, selection highlight) right below, each with
     * its own border and background. Position was already resolved by OverlayManager.
     */
    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        IMEBridge.CompositionInfo info = IMEHandler.getCompositionInfo();
        IMEHandler.CandidatePage page = visibleCandidatePage();
        if (info == null && page == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRenderer;
        int x = this.x;
        int boxY = this.y;

        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // ── PreEdit 框：逐分段绘制，聚焦段亮色 + 加粗下划线，组字内光标竖线 ──
        // ── PreEdit box: per-block, focused block bright + thick underline, caret bar ──
        if (info != null && preeditBoxH > 0) {
            drawBox(x, boxY, preeditBoxW, preeditBoxH);
            int bx = x + 1 + PADDING;
            int ty = boxY + 1 + PADDING;
            int underlineY = ty + fr.FONT_HEIGHT;
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
                fr.drawStringWithShadow(block, bx, ty, focused ? COLOR_FOCUSED : COLOR_TEXT);
                int bw = fr.getStringWidth(block);
                Gui.drawRect(bx, underlineY, bx + bw, underlineY + (focused ? 2 : 1), 0xFFFFFFFF);
                bx += bw + (i < info.blocks.length - 1 ? 1 : 0);
                charOffset += block.length();
            }
            Gui.drawRect(caretX, ty - 1, caretX + 1, ty + fr.FONT_HEIGHT, COLOR_CARET);
            boxY += preeditBoxH + BOX_GAP;
        }

        // ── 候选框（仅独占全屏）：横向排布，选中项浅底深字高亮 ──
        // ── Candidate box (fullscreen only): horizontal items, selection dark-on-light ──
        if (page != null && candBoxH > 0) {
            drawBox(x, boxY, candBoxW, candBoxH);
            int px = x + 1 + PADDING;
            int ty = boxY + 1 + PADDING;
            for (int i = 0; i < page.texts.length; i++) {
                String label = candidateLabel(i, page.texts[i]);
                int lw = fr.getStringWidth(label);
                if (i == page.selectedIndex) {
                    Gui.drawRect(px - 1, ty - 1, px + lw + 1, ty + fr.FONT_HEIGHT, COLOR_SELECTED_BG);
                    fr.drawString(label, px, ty, COLOR_SELECTED_TEXT);
                } else {
                    fr.drawStringWithShadow(label, px, ty, COLOR_TEXT);
                }
                px += lw + ITEM_GAP;
            }
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** 画一个 1px 边框 + 深色底的框体 / Draws one bordered dark box */
    private static void drawBox(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, COLOR_BORDER);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, COLOR_BG);
    }
}
