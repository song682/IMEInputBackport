package decok.dfcdvadstf.imeinput.compat;

import java.util.List;

import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import decok.dfcdvadstf.imeinput.mixin.MixinGuiScreen;
import decok.dfcdvadstf.imeinput.mixin.MixinGuiScreenBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

/**
 * 原版"书与笔"界面的内置 IME 提交目标：{@link GuiScreenBook} 不使用
 * {@code GuiTextField}（页面文本存于 NBT 并自绘），默认焦点路径不会为它
 * 激活 IME，故以 {@link IMECommitTarget} 显式接入：
 * <ul>
 * <li>{@link #shouldReceiveInput()}：当前界面是可编辑（未署名）的书时激活，
 * 覆盖正文编辑与署名（书名输入）两种模式</li>
 * <li>{@link #commitText(String)}：逐字符经 {@code ChatAllowedCharacters}
 * 过滤后以 {@code Keyboard.KEY_NONE} 注入屏幕 {@code keyTyped}，复刻真实
 * 按键路径——正文走 {@code keyTypedInBook}（行宽 118 / 单页 256 字符限制
 * 与换行逻辑全部生效），署名模式走 {@code func_146460_c}（书名 16 字符
 * 限制生效）</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()}：按原版渲染布局重算闪烁
 * 光标 "_" 的位置（正文：按 116 宽折行取末行行尾；书名：居中排布的文本
 * 末尾），作为候选窗锚点</li>
 * </ul>
 * Built-in IME commit target for the vanilla book & quill screen:
 * {@link GuiScreenBook} does not use {@code GuiTextField} (page text lives in
 * NBT and is self-drawn), so the default focus path never enables the IME
 * there — this target hooks in explicitly:
 * <ul>
 * <li>{@link #shouldReceiveInput()}: active while an editable (unsigned) book
 * screen is open, covering both body editing and signing (title input)</li>
 * <li>{@link #commitText(String)}: char-by-char through
 * {@code ChatAllowedCharacters} and injected into the screen's {@code keyTyped}
 * with {@code Keyboard.KEY_NONE}, mirroring real key presses — body input goes
 * through {@code keyTypedInBook} (118 px line width / 256-char page limits and
 * line breaking all apply), signing goes through {@code func_146460_c}
 * (16-char title limit applies)</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()}: recompute the blinking "_"
 * caret position from the vanilla render layout (body: end of the last line
 * wrapped at width 116; title: end of the centred text) as the candidate
 * anchor</li>
 * </ul>
 */
public final class VanillaBookCompact implements IMECommitTarget {

    /** 单例：书界面的提交目标无状态，注册一次即可 */
    private static final VanillaBookCompact INSTANCE = new VanillaBookCompact();

    /** 正文版心宽度（原版 drawSplitString / splitStringWidth 使用的折行宽度） */
    private static final int PAGE_TEXT_WIDTH = 116;
    /** 正文文本区左上角相对书本贴图的偏移（原版 drawScreen：k+36, b0+16+16） */
    private static final int PAGE_TEXT_X = 36;
    private static final int PAGE_TEXT_Y = 2 + 16 + 16;
    /** 署名模式下书名绘制行（原版 drawScreen：b0+48） */
    private static final int TITLE_Y = 2 + 48;

    private VanillaBookCompact() {
    }

    /**
     * 注册书界面提交目标（原版界面恒存在，无需前置检查）。
     * Registers the book screen target (vanilla screen, always present).
     */
    public static void register() {
        IMEInputAPI.register(INSTANCE);
    }

    /**
     * 当前打开的是未署名（可编辑）的书界面时激活。已署名的书为只读，
     * 不接收输入。
     * Active while an unsigned (editable) book screen is open; signed books
     * are read-only and never receive input.
     *
     * @return true = 可编辑的书界面打开中
     */
    @Override
    public boolean shouldReceiveInput() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        return screen instanceof GuiScreenBook
                && ((MixinGuiScreenBook) screen).imeinput$isBookUnsigned();
    }

    /**
     * 逐字符注入书界面的 keyTyped：正文模式由 {@code keyTypedInBook} 处理
     * 插入与页容量限制，署名模式由 {@code func_146460_c} 处理书名追加与
     * 16 字符上限。过滤非法字符（含控制字符）避免误触粘贴分支
     * （{@code keyTypedInBook} 对字符 22 执行剪贴板粘贴）。
     * Char-by-char injection into the book screen's keyTyped: body mode goes
     * through {@code keyTypedInBook} (insertion + page capacity limits), title
     * mode through {@code func_146460_c} (append + 16-char cap). Illegal and
     * control characters are filtered so the paste branch ({@code keyTypedInBook}
     * pastes the clipboard for char 22) can never trigger.
     *
     * @param text 提交文本（非空）
     */
    @Override
    public void commitText(String text) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiScreenBook)) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                ((MixinGuiScreen) screen).imeinput$keyTyped(c, Keyboard.KEY_NONE);
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
     * @return {x, y} GUI 逻辑坐标；界面状态不可用时返回 null
     */
    private int[] resolveCaret() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiScreenBook)) {
            return null;
        }
        MixinGuiScreenBook book = (MixinGuiScreenBook) screen;
        if (!book.imeinput$isBookUnsigned()) {
            return null;
        }
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int k = (screen.width - book.imeinput$getBookImageWidth()) / 2;
        if (book.imeinput$isInTitleMode()) {
            int w = fr.getStringWidth(book.imeinput$getBookTitle());
            return new int[] { k + PAGE_TEXT_X + (PAGE_TEXT_WIDTH + w) / 2,
                    TITLE_Y + fr.FONT_HEIGHT + 2 };
        }
        String text = currentPageText(book);
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
     * @param book 书界面访问器
     * @return 当前页文本
     */
    private String currentPageText(MixinGuiScreenBook book) {
        NBTTagList pages = book.imeinput$getBookPages();
        int page = book.imeinput$getCurrPage();
        if (pages != null && page >= 0 && page < pages.tagCount()) {
            return pages.getStringTagAt(page);
        }
        return "";
    }
}
