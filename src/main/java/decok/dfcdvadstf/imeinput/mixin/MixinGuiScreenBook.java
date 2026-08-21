package decok.dfcdvadstf.imeinput.mixin;

import java.util.List;

import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * {@link GuiScreenBook}（书与笔/署名界面）的 IME 接入混入：原版书界面不使用
 * {@code GuiTextField}（页面文本存于 NBT 并自绘），默认焦点路径不会为它
 * 激活 IME。纯原版界面不走 {@code IMEInputAPI} 注册（该 API 留给第三方
 * 模组的自绘控件），而是由本混入把 {@link IMECommitTarget} 接口直接注入
 * 到 {@code GuiScreenBook} 上，{@code IMEHandler} 以 {@code instanceof}
 * 识别屏幕自身即目标：
 * <ul>
 * <li>{@link #shouldReceiveInput()}：书未署名（可编辑）时激活，覆盖正文
 * 编辑与署名（书名输入）两种模式</li>
 * <li>{@link #commitText(String)}：逐字符经 {@code ChatAllowedCharacters}
 * 过滤后以 {@code Keyboard.KEY_NONE} 调用自身 {@code keyTyped}，复刻真实
 * 按键路径——正文走 {@code keyTypedInBook}（行宽 118 / 单页 256 字符限制
 * 与换行逻辑全部生效），署名模式走 {@code func_146460_c}（书名 16 字符
 * 限制生效）</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()}：按原版渲染布局重算闪烁
 * 光标 "_" 的位置（正文：按 116 宽折行取末行行尾；书名：居中排布的文本
 * 末尾），作为候选窗锚点</li>
 * </ul>
 * IME integration mixin for {@link GuiScreenBook} (book & quill / signing
 * screen): the vanilla book UI does not use {@code GuiTextField} (page text
 * lives in NBT and is self-drawn), so the default focus path never enables
 * the IME there. Pure vanilla screens do not register through
 * {@code IMEInputAPI} (that API is reserved for third-party mods' self-drawn
 * controls); instead this mixin injects the {@link IMECommitTarget} interface
 * straight into {@code GuiScreenBook}, and {@code IMEHandler} recognises the
 * screen itself as the target via {@code instanceof}:
 * <ul>
 * <li>{@link #shouldReceiveInput()}: active while the book is unsigned
 * (editable), covering both body editing and signing (title input)</li>
 * <li>{@link #commitText(String)}: char-by-char through
 * {@code ChatAllowedCharacters} and fed into our own {@code keyTyped} with
 * {@code Keyboard.KEY_NONE}, mirroring real key presses — body input goes
 * through {@code keyTypedInBook} (118 px line width / 256-char page limits
 * and line breaking all apply), signing goes through {@code func_146460_c}
 * (16-char title limit applies)</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()}: recompute the blinking
 * "_" caret from the vanilla render layout (body: end of the last line
 * wrapped at width 116; title: end of the centred text) as the candidate
 * anchor</li>
 * </ul>
 */
@Mixin(GuiScreenBook.class)
public abstract class MixinGuiScreenBook implements IMECommitTarget {

    /** 正文版心宽度（原版 drawSplitString / splitStringWidth 使用的折行宽度） */
    private static final int PAGE_TEXT_WIDTH = 116;
    /** 正文文本区左上角相对书本贴图的偏移（原版 drawScreen：k+36, b0+16+16） */
    private static final int PAGE_TEXT_X = 36;
    private static final int PAGE_TEXT_Y = 2 + 16 + 16;
    /** 署名模式下书名绘制行（原版 drawScreen：b0+48） */
    private static final int TITLE_Y = 2 + 48;

    /** 书是否未署名（未署名才可编辑；已署名为只读） / Whether the book is unsigned (editable) */
    @Shadow
    private boolean bookIsUnsigned;

    /** 是否处于署名模式（书名输入） / Whether the signing (title input) mode is active */
    @Shadow
    private boolean field_146480_s;

    /** 书页内容（每页一个 NBTTagString） / Page contents (one NBTTagString per page) */
    @Shadow
    private NBTTagList bookPages;

    /** 当前页码（0 起） / Current page index (0-based) */
    @Shadow
    private int currPage;

    /** 署名模式下的书名 / Book title in signing mode */
    @Shadow
    private String bookTitle;

    /** 书本贴图宽度（定位版心用） / Book texture width (locating the page area) */
    @Shadow
    private int bookImageWidth;

    /**
     * 影子方法：供 {@link #commitText(String)} 模拟按键注入。
     * Shadowed so {@link #commitText(String)} can inject simulated key presses.
     */
    @Shadow
    protected abstract void keyTyped(char typedChar, int keyCode);

    /**
     * 书未署名（可编辑）时接收 IME 输入；已署名的书为只读。屏幕自身即目标，
     * 由 {@code IMEHandler} 经 {@code instanceof} 直接识别，无需注册。
     * Receives IME input while the book is unsigned (editable); signed books
     * are read-only. The screen itself is the target — {@code IMEHandler}
     * recognises it via {@code instanceof}, no registration needed.
     *
     * @return true = 可编辑的书界面
     */
    @Override
    public boolean shouldReceiveInput() {
        return bookIsUnsigned;
    }

    /**
     * 逐字符注入自身 keyTyped：正文模式由 {@code keyTypedInBook} 处理插入
     * 与页容量限制，署名模式由 {@code func_146460_c} 处理书名追加与 16 字符
     * 上限。过滤非法字符（含控制字符）避免误触粘贴分支（{@code keyTypedInBook}
     * 对字符 22 执行剪贴板粘贴）。
     * Char-by-char injection into our own keyTyped: body mode goes through
     * {@code keyTypedInBook} (insertion + page capacity limits), title mode
     * through {@code func_146460_c} (append + 16-char cap). Illegal and control
     * characters are filtered so the paste branch ({@code keyTypedInBook}
     * pastes the clipboard for char 22) can never trigger.
     *
     * @param text 提交文本（非空）
     */
    @Override
    public void commitText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                keyTyped(c, Keyboard.KEY_NONE);
            }
        }
    }

    /**
     * 候选窗锚点 X：按原版渲染布局计算光标 "_" 所在水平位置。
     * Candidate anchor X: cursor "_" position recomputed from the vanilla
     * render layout.
     *
     * @return 锚点 GUI X；-1 = 回退默认
     */
    @Override
    public int getCaretX() {
        int[] pos = resolveCaret();
        return pos != null ? pos[0] : -1;
    }

    /**
     * 候选窗锚点 Y：光标 "_" 下沿（末行底部 + 2，与 MixinGuiTextField 口径一致）。
     * Candidate anchor Y: below the cursor "_" (bottom of the last line + 2,
     * consistent with MixinGuiTextField).
     *
     * @return 锚点 GUI Y；-1 = 回退默认
     */
    @Override
    public int getCaretY() {
        int[] pos = resolveCaret();
        return pos != null ? pos[1] : -1;
    }

    /**
     * 按原版 drawScreen 的布局重算闪烁光标位置：
     * 正文模式——当前页文本按 116 宽折行，光标在末行行尾
     * （x = k+36+末行宽度，y = 34+行数*行高+2）；
     * 署名模式——书名居中绘制（x = k+36+(116-宽)/2），光标在文本末尾。
     * Recomputes the blinking caret from the vanilla drawScreen layout: in
     * page mode the current page text wraps at width 116 and the caret sits at
     * the end of the last line; in signing mode the title is centred and the
     * caret sits after it.
     *
     * @return {x, y} GUI 逻辑坐标
     */
    private int[] resolveCaret() {
        GuiScreen self = (GuiScreen) (Object) this;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int k = (self.width - bookImageWidth) / 2;
        if (field_146480_s) {
            int w = fr.getStringWidth(bookTitle);
            return new int[] { k + PAGE_TEXT_X + (PAGE_TEXT_WIDTH + w) / 2,
                    TITLE_Y + fr.FONT_HEIGHT + 2 };
        }
        String text = currentPageText();
        List<?> lines = fr.listFormattedStringToWidth(text, PAGE_TEXT_WIDTH);
        String lastLine = lines.isEmpty() ? "" : (String) lines.get(lines.size() - 1);
        int rowCount = Math.max(1, lines.size());
        return new int[] { k + PAGE_TEXT_X + fr.getStringWidth(lastLine),
                PAGE_TEXT_Y + rowCount * fr.FONT_HEIGHT + 2 };
    }

    /**
     * 读取当前页文本（页码越界或页面列表为空时返回空串）。
     * Reads the current page text (empty string when the page index is out of
     * range or the page list is absent).
     *
     * @return 当前页文本
     */
    private String currentPageText() {
        if (bookPages != null && currPage >= 0 && currPage < bookPages.tagCount()) {
            return bookPages.getStringTagAt(currPage);
        }
        return "";
    }
}
