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
import org.lwjgl.opengl.GL11;

/**
 * IME 自绘组词窗 Overlay（接入 CatFrame Overlay 体系）。
 * <p>
 * 自绘部分默认呈现为<b>单一窗体</b>：PreEdit 区（组字分段高亮 + 光标）与自绘候选区
 * 合并在同一个窗体内，中间以一条内嵌分隔线分区；是否合并由主题选项
 * {@code mergeSections} 决定（{@link IMETheme#mergeSections()}，设为 false 时
 * 两区各自独立窗体上下堆叠）：
 * <pre>
 * ┌──────────────────────────┐
 * │ 组字串（分段 + 聚焦段 + 光标）│  ← PreEdit 区（本 Overlay 自绘，任何模式）
 * │ ──────────────────────── │  ← 内嵌分隔线（两区同窗时才有）
 * │ 1. 候选  2. 候选  3. 候选   │  ← 候选区（selfDrawCandidates 时并入本窗）
 * └──────────────────────────┘
 * </pre>
 * 候选框的归属由配置 {@code candidateNativeBar} 决定：默认（true）由当前活动
 * 输入法自己画——{@link IMEHandler} 用 CFS_POINT / GetTextExt 把系统组词窗下压到
 * PreEdit 窗体下沿，两窗上下相接（外部窗口无法并入自绘窗体）；设为 false 时
 * 本地层隐藏原生候选窗，候选区并入本窗体由 {@link #selfDrawCandidates} 接管。
 * 窗体外观（平面自绘 / 九宫格纹理）与配色统一由 {@link IMETheme} 掌管。
 * <p>
 * IME self-drawn composition overlay (plugged into the CatFrame Overlay system).
 * By default the self-drawn part renders as a <b>single window</b>: the PreEdit
 * section (block highlight + caret) and the self-drawn candidate section share
 * one window chrome, split by an inset separator line; whether they merge is a
 * theme option ({@link IMETheme#mergeSections()} — false stacks two standalone
 * windows instead). Ownership of the candidate
 * UI follows the {@code candidateNativeBar} config: by default (true) the
 * active IME draws its own external window — {@link IMEHandler} pushes it
 * (CFS_POINT / GetTextExt) right below the PreEdit window, as an external
 * window can never merge into ours; when set to false the native layer hides
 * the OS candidate UI and the candidate section joins this window via
 * {@link #selfDrawCandidates}. Window chrome (flat / nine-slice texture) and
 * the palette are owned by {@link IMETheme}.
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

    /**
     * 自绘候选框开关：由配置 {@code candidateNativeBar} 驱动
     * （{@link IMEHandler#init} 写入，取其反值）。默认 false——候选框由
     * 当前活动输入法自己画；配置设为 candidateNativeBar=false 时为 true，
     * 本类接管候选框绘制（本地层同步隐藏原生候选窗）。
     * Self-drawn candidate box switch, driven by the {@code candidateNativeBar}
     * config (written by {@link IMEHandler#init} as its negation). false by
     * default — the active IME draws the candidate box itself; true when the
     * config opts out of the native bar, making this class take over (the
     * native layer hides the OS candidate UI in step).
     */
    public static volatile boolean selfDrawCandidates = false;

    // ──── 布局常量 / Layout constants ────
    // 配色与窗体外观由 IMETheme 从资源包主题加载
    // Palette & window chrome are loaded from the resource-pack theme by IMETheme

    /** 窗体内边距 / Window padding */
    private static final int PADDING = 3;
    /** 分隔线区总高：上 2px 空隙 + 1px 线 + 下 2px 空隙 / Separator zone: 2+1+2 */
    private static final int SEPARATOR_ZONE = 5;
    /** 分离双窗模式下两窗体的垂直间隙 / Vertical gap between split twin windows */
    private static final int WINDOW_GAP = 2;
    /** 候选项之间的间距 / Gap between candidate items */
    private static final int ITEM_GAP = 6;

    // ──── 布局缓存（isVisible 中重算，render 中消费）────
    // ──── Layout cache (recomputed in isVisible, consumed by render) ────

    /** PreEdit 区内容高（0 表示本帧无此区）/ PreEdit section content height, 0 = absent */
    private int preeditSectionH;
    /** 候选区内容高（0 表示本帧无此区）/ Candidate section content height, 0 = absent */
    private int candSectionH;
    /** PreEdit 区内容宽 / PreEdit section content width */
    private int preeditContentW;
    /** 候选区内容宽 / Candidate section content width */
    private int candContentW;
    /** 本帧是否分离双窗（两区同在且主题不合并）/ Split twin-window mode this frame */
    private boolean splitWindows;

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
     * 显示门控：IME 激活 + 有组字（任何模式）；自绘候选仅在开关启用时参与。
     * 通过时顺带重算浮窗尺寸——OverlayManager 在本调用之后立刻用
     * {@code getWidth()/getHeight()} 解析锚点，此处是唯一先于取值的稳定钩子。
     * Visibility gate: IME active + composing (any display mode); the self-drawn
     * candidates only participate when the switch is on.
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
     * 当前应自绘的候选页：仅开关启用时返回快照，原生候选条模式下恒为 null
     * （候选框由当前活动输入法自己画，本 Overlay 不插手）。
     * The candidate page to self-draw: only when the switch is enabled; null in
     * native-bar mode — the active IME draws the candidate box itself.
     */
    private static IMEHandler.CandidatePage visibleCandidatePage() {
        return selfDrawCandidates ? IMEHandler.getCandidatePage() : null;
    }

    // ──── 布局 / Layout ────

    /**
     * 依据当前组字与候选快照重算两区内容尺寸与总包围盒：
     * 合并模式下窗宽取两区内容宽的较大者，两区同窗时中间追加一条分隔线区；
     * 分离模式（主题 mergeSections=false 且两区同在）下两窗体各自收紧到
     * 自身内容宽，包围盒取两窗并集（供 OverlayManager 解析锚点与越界钳位）。
     * Recomputes both section sizes and the total bounding box: in merged mode
     * the window width is the wider of the two sections, with a separator zone
     * inserted when both share the window; in split mode (theme
     * mergeSections=false with both sections present) each window shrinks to
     * its own content width and the bounding box is their union.
     */
    private void relayout(FontRenderer fr, IMEBridge.CompositionInfo info, IMEHandler.CandidatePage page) {
        preeditSectionH = 0;
        candSectionH = 0;
        preeditContentW = 0;
        candContentW = 0;

        if (info != null) {
            for (int i = 0; i < info.blocks.length; i++) {
                preeditContentW += fr.getStringWidth(info.blocks[i]);
                if (i < info.blocks.length - 1) {
                    preeditContentW += 1; // 分段间 1px 缝隙 / 1px inter-block gap
                }
            }
            // 文本 + 下划线 / text + underline
            preeditSectionH = fr.FONT_HEIGHT + 2;
        }

        if (page != null) {
            for (int i = 0; i < page.texts.length; i++) {
                candContentW += fr.getStringWidth(candidateLabel(i, page.texts[i]));
                if (i < page.texts.length - 1) {
                    candContentW += ITEM_GAP;
                }
            }
            candSectionH = fr.FONT_HEIGHT;
        }

        // 分离双窗仅在两区同在且主题选择不合并时生效；单区时两种模式无差别
        // Split twin windows only when both sections exist and the theme opts out
        splitWindows = preeditSectionH > 0 && candSectionH > 0 && !IMETheme.mergeSections();

        // 周边开销：内边距 + 1px 边框 / Chrome overhead: padding + 1px border
        int chromeW = PADDING * 2 + 2;
        int chromeH = PADDING * 2 + 2;
        if (splitWindows) {
            // 包围盒 = 两窗并集：宽取较宽窗，高为两窗叠加 + 窗间隙
            // Bounding box = union: wider window's width, both heights + gap
            this.width = Math.max(preeditContentW, candContentW) + chromeW;
            this.height = (preeditSectionH + chromeH) + WINDOW_GAP + (candSectionH + chromeH);
        } else {
            // 单一窗体：内容 + 内边距 + 1px 边框，两区同窗时插入分隔线区
            // Single window: content + padding + border, separator when shared
            this.width = Math.max(preeditContentW, candContentW) + chromeW;
            this.height = preeditSectionH + candSectionH
                    + (preeditSectionH > 0 && candSectionH > 0 ? SEPARATOR_ZONE : 0)
                    + chromeH;
        }
    }

    /**
     * PreEdit 独立窗体总高（GUI 单位，含内边距与边框）：原生候选条模式下
     * 窗体内只有 PreEdit 区时的窗高。供 {@link IMEHandler} 把系统组词窗
     * 下压到本窗下沿，使内外两窗上下相接。
     * Total height of the standalone PreEdit window (GUI units, padding and
     * border included) — the window height when only the PreEdit section is
     * present (native candidate bar mode). Used by {@link IMEHandler} to push
     * the OS composition window right below it.
     *
     * @param fr 字体渲染器 / font renderer
     * @return 窗高（GUI 单位）/ window height in GUI units
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
     * 绘制组词窗：窗体（边框 + 底）由 {@link IMETheme} 按当前风格绘制；
     * 合并模式（主题 mergeSections=true，默认）下整窗一次绘制，窗内
     * PreEdit 区（分段高亮 + 光标）在上，自绘候选区（candidateNativeBar=false
     * 时启用，选中项高亮）在下，两区同窗时中间画一条内嵌分隔线；
     * 分离模式下两区各自独立窗体上下堆叠，各自收紧到自身内容宽。
     * 坐标已由 OverlayManager 依锚点与 offset 解析并临时写入 x/y。
     * Draws the composition UI: {@link IMETheme} paints each window chrome for
     * the current style. Merged mode (theme mergeSections=true, default) paints
     * one window — PreEdit section (block highlight + caret) on top, self-drawn
     * candidate section (enabled via candidateNativeBar=false, selection
     * highlight) below, split by an inset separator when both are present.
     * Split mode stacks two standalone windows, each shrunk to its own content
     * width. Position was resolved by OverlayManager.
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

        GL11.glDisable(GL11.GL_DEPTH_TEST);

        if (splitWindows) {
            // ── 分离双窗：两窗体各自绘制，上下以 WINDOW_GAP 相隔；
            //    上窗用主题的独立 PreEdit 皮肤，下窗用基础皮肤 ──
            // ── Split twin windows, WINDOW_GAP apart: top window wears the
            //    theme's standalone PreEdit skin, bottom the base skin ──
            int chrome = PADDING * 2 + 2;
            int preeditWinH = preeditSectionH + chrome;
            IMETheme.drawPreeditWindow(x, this.y, preeditContentW + chrome, preeditWinH);
            drawPreeditSection(fr, info, x + 1 + PADDING, this.y + 1 + PADDING);

            int candWinY = this.y + preeditWinH + WINDOW_GAP;
            IMETheme.drawWindow(x, candWinY, candContentW + chrome, candSectionH + chrome);
            drawCandidateSection(fr, page, x + 1 + PADDING, candWinY + 1 + PADDING);
        } else {
            // ── 单一窗体：整窗一次绘制（FLAT 双矩形 / TEXTURE 九宫格），交给主题。
            //    窗内只有 PreEdit 且它本就独立成窗（原生候选条模式 / 分离模式候选
            //    暂缺）时用 PreEdit 皮肤；合并模式始终基础皮肤，免得候选出没时窗体风格闪变 ──
            // ── Single window, chrome painted once by the theme. A PreEdit-only
            //    window that is standalone by nature (native bar mode / split mode
            //    with candidates momentarily absent) wears the PreEdit skin;
            //    merged mode sticks to the base skin so the chrome never flips
            //    as candidates come and go ──
            boolean standalonePreedit = preeditSectionH > 0 && candSectionH == 0
                    && !(selfDrawCandidates && IMETheme.mergeSections());
            if (standalonePreedit) {
                IMETheme.drawPreeditWindow(x, this.y, this.width, this.height);
            } else {
                IMETheme.drawWindow(x, this.y, this.width, this.height);
            }

            int cx = x + 1 + PADDING;
            int cy = this.y + 1 + PADDING;

            if (info != null && preeditSectionH > 0) {
                drawPreeditSection(fr, info, cx, cy);
                cy += preeditSectionH;
            }

            // ── 分隔线：两区同窗时居中于分隔线区 / Separator, centered in its zone ──
            if (info != null && preeditSectionH > 0 && page != null && candSectionH > 0) {
                IMETheme.drawSeparator(x, cy + SEPARATOR_ZONE / 2, this.width);
                cy += SEPARATOR_ZONE;
            }

            if (page != null && candSectionH > 0) {
                drawCandidateSection(fr, page, cx, cy);
            }
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 绘制 PreEdit 区内容：逐分段绘制，聚焦段亮色 + 加粗下划线，组字内光标竖线。
     * Draws the PreEdit section content: per-block, focused block bright with a
     * thick underline, plus the in-composition caret bar.
     *
     * @param cx 内容区左上角 GUI X / content top-left GUI X
     * @param cy 内容区左上角 GUI Y / content top-left GUI Y
     */
    private static void drawPreeditSection(FontRenderer fr, IMEBridge.CompositionInfo info, int cx, int cy) {
        if (info == null) {
            return;
        }
        int bx = cx;
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
            fr.drawStringWithShadow(block, bx, cy,
                    focused ? IMETheme.focusedTextColor() : IMETheme.textColor());
            int bw = fr.getStringWidth(block);
            Gui.drawRect(bx, underlineY, bx + bw, underlineY + (focused ? 2 : 1),
                    IMETheme.underlineColor());
            bx += bw + (i < info.blocks.length - 1 ? 1 : 0);
            charOffset += block.length();
        }
        Gui.drawRect(caretX, cy - 1, caretX + 1, cy + fr.FONT_HEIGHT, IMETheme.caretColor());
    }

    /**
     * 绘制自绘候选区内容（candidateNativeBar=false 时启用）：横向排布，
     * 选中项浅底深字高亮。
     * Draws the self-drawn candidate section content (enabled via
     * candidateNativeBar=false): laid out horizontally, selection highlighted
     * with a light background and dark text.
     *
     * @param cx 内容区左上角 GUI X / content top-left GUI X
     * @param cy 内容区左上角 GUI Y / content top-left GUI Y
     */
    private static void drawCandidateSection(FontRenderer fr, IMEHandler.CandidatePage page, int cx, int cy) {
        if (page == null) {
            return;
        }
        int px = cx;
        for (int i = 0; i < page.texts.length; i++) {
            String label = candidateLabel(i, page.texts[i]);
            int lw = fr.getStringWidth(label);
            if (i == page.selectedIndex) {
                Gui.drawRect(px - 1, cy - 1, px + lw + 1, cy + fr.FONT_HEIGHT,
                        IMETheme.selectedBackgroundColor());
                fr.drawString(label, px, cy, IMETheme.selectedTextColor());
            } else {
                fr.drawStringWithShadow(label, px, cy, IMETheme.textColor());
            }
            px += lw + ITEM_GAP;
        }
    }
}
