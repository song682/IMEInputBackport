package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.nbt.NBTTagList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link GuiScreenBook}（书与笔/署名界面）私有状态访问器：原版书界面不使用
 * {@code GuiTextField}，页面文本与书名直接存于私有字段并自绘渲染；本接口把
 * IME 接入所需的状态（是否可编辑、署名模式、当前页文本、书名、版心宽度）
 * 暴露给 {@code VanillaBookCompact} 兼容目标，用于模拟输入路由与候选窗锚点计算。
 * <p>
 * Private-state accessors for {@link GuiScreenBook} (book & quill / signing
 * screen): the vanilla book UI does not use {@code GuiTextField} — page text
 * and title live in private fields and are self-drawn. This interface exposes
 * the state the IME integration needs (editability, signing mode, current page
 * text, title, page width) to the {@code VanillaBookCompact} commit target for
 * commit routing and candidate-window anchor computation.
 */
@Mixin(GuiScreenBook.class)
public interface MixinGuiScreenBook {

    /**
     * 书是否未署名（未署名才可编辑页面/署名；已署名为只读）。
     * Whether the book is unsigned (only unsigned books allow page/title
     * editing; signed books are read-only).
     *
     * @return true = 可编辑
     */
    @Accessor("bookIsUnsigned")
    boolean imeinput$isBookUnsigned();

    /**
     * 是否处于署名模式（点击"签名"按钮后切换为书名输入，原版字段
     * {@code field_146480_s}，MCP 与 SRG 同名）。
     * Whether the signing mode is active (after the "Sign" button the screen
     * switches to title input; vanilla field {@code field_146480_s}, identical
     * MCP/SRG name).
     *
     * @return true = 书名输入模式
     */
    @Accessor("field_146480_s")
    boolean imeinput$isInTitleMode();

    /**
     * 书页内容（每页一个 {@code NBTTagString}）。
     * Page contents (one {@code NBTTagString} per page).
     *
     * @return 页面标签列表
     */
    @Accessor("bookPages")
    NBTTagList imeinput$getBookPages();

    /**
     * 当前页码（0 起）。
     * Current page index (0-based).
     *
     * @return 当前页码
     */
    @Accessor("currPage")
    int imeinput$getCurrPage();

    /**
     * 署名模式下的书名（最长 16 字符，由原版 keyTyped 限制）。
     * Book title in signing mode (max 16 chars, enforced by vanilla keyTyped).
     *
     * @return 当前书名
     */
    @Accessor("bookTitle")
    String imeinput$getBookTitle();

    /**
     * 书本贴图宽度（用于定位版心，原版常量 192）。
     * Book texture width (used to locate the page area, vanilla constant 192).
     *
     * @return 贴图宽度
     */
    @Accessor("bookImageWidth")
    int imeinput$getBookImageWidth();
}
