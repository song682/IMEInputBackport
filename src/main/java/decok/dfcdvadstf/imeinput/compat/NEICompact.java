package decok.dfcdvadstf.imeinput.compat;

import codechicken.nei.LayoutManager;
import codechicken.nei.TextField;
import codechicken.nei.Widget;
import codechicken.nei.guihook.GuiContainerManager;
import cpw.mods.fml.common.Loader;
import decok.dfcdvadstf.imeinput.IMEHandler;
import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;

/**
 * NEI（GTNH 非官方版）兼容层——{@link IMECommitTarget} 的首个内置实现。
 * <p>
 * 设计对齐 IngameIME 的 NEI 集成（{@code Internal.commitCallback}）：IME 提交文本
 * 时逐字符走 {@link GuiContainerManager#keyTyped(char, int)} 管线，由 NEI 的
 * {@link LayoutManager} 路由到当前聚焦的 {@link TextField}（搜索框/数量框），
 * 并触发其 {@code onTextChange} 刷新搜索结果——直接向内部 GuiTextField
 * {@code writeText} 会绕过刷新，搜索/数量不会更新。
 * <p>
 * 激活判定比 IngameIME 的无条件分支更严：{@link #shouldReceiveInput()} 要求当前处于容器
 * 界面（{@code GuiContainerManager.getManager()} 非空，非容器屏幕含 NEI 设置界面
 * 自动落回普通注入路径），且 {@code LayoutManager.getInputFocused()} 指向某个聚焦
 * 的 {@link TextField}，且其内部 {@code field}（反射读取 protected 字段）正是
 * {@link IMEHandler} 当前追踪的聚焦文本框——避免容器界面内 mod 自身文本框
 * （如 AE 终端搜索框）的提交被 NEI 管线截走。
 * <p>
 * 类加载安全：静态初始化不触碰任何 codechicken 类；所有 NEI 访问都发生在
 * {@link #isLoaded()} 之后，未安装 NEI 时不会触发 codechicken 类加载。
 * <p>
 * NEI (GTNH unofficial fork) compatibility layer — the first built-in
 * {@link IMECommitTarget} implementation.
 * <p>
 * Design mirrors IngameIME's NEI integration ({@code Internal.commitCallback}):
 * committed text is fed char-by-char into the
 * {@link GuiContainerManager#keyTyped}
 * pipeline so NEI's {@link LayoutManager} routes it to the focused
 * {@link TextField}
 * (search/quantity box) and its {@code onTextChange} fires — a plain
 * {@code writeText} into the inner GuiTextField would skip the refresh.
 * <p>
 * Activation is stricter than IngameIME's unconditional branch:
 * {@link #shouldReceiveInput()}
 * requires a container screen ({@code GuiContainerManager.getManager()}
 * non-null;
 * non-container screens, including the NEI options screen, fall back to the
 * normal
 * injection path), {@code LayoutManager.getInputFocused()} pointing at a
 * focused
 * {@link TextField}, and that TextField's inner {@code field} (read
 * reflectively)
 * being exactly the field currently tracked by {@link IMEHandler} — so commits
 * aimed at a mod's own text field inside a container (e.g. AE terminal search)
 * are
 * never hijacked by the NEI pipeline.
 * <p>
 * Class-load safety: the static initializer touches no codechicken class; all
 * NEI
 * access happens after {@link #isLoaded()}, so no codechicken class is loaded
 * when
 * NEI is absent.
 */
public final class NEICompact implements IMECommitTarget {

    /** 单例：注册进 {@link IMEInputAPI} 的实例 */
    private static final NEICompact INSTANCE = new NEICompact();

    /** {@code TextField.field}（protected GuiTextField）反射句柄；null 表示未解析或解析失败 */
    private static Field textFieldField = null;
    /** 是否已完成字段解析（防止反复抛异常） */
    private static boolean fieldLookupDone = false;

    private NEICompact() {
    }

    /**
     * NEI 已加载时注册为 IME 提交目标（客户端初始化调用一次）。
     * Registers as an IME commit target when NEI is loaded (call once at client
     * init).
     */
    public static void register() {
        if (isLoaded()) {
            IMEInputAPI.register(INSTANCE);
        }
    }

    /**
     * NEI 是否已加载（modid = {@code NotEnoughItems}，官方版与 GTNH 非官方版同）。
     * Whether NEI is loaded (modid {@code NotEnoughItems}, same for the official
     * release and the GTNH unofficial fork).
     *
     * @return true = 已加载
     */
    public static boolean isLoaded() {
        return Loader.isModLoaded("NotEnoughItems");
    }

    @Override
    public boolean shouldReceiveInput() {
        // 非容器界面（含 NEI 设置界面）：NEI 管线不可用，落回普通注入
        // Not on a container screen (incl. NEI options): pipeline unavailable
        if (GuiContainerManager.getManager() == null) {
            return false;
        }
        Widget focused = LayoutManager.getInputFocused();
        if (!(focused instanceof TextField)) {
            return false;
        }
        TextField textField = (TextField) focused;
        if (!textField.focused()) {
            return false;
        }
        // 与 Mixin 追踪的聚焦文本框对照：防止容器切换后 NEI 遗留焦点的误判
        // Cross-check with the Mixin-tracked focused field: guards against a
        // stale NEI focus surviving a container switch
        return resolveInternalField(textField) == IMEHandler.getFocusedField();
    }

    @Override
    public void commitText(String text) {
        // 把 IME 提交文本逐字符注入 NEI 按键管线（对齐 IngameIME）：
        // GuiContainerManager.keyTyped(c, Keyboard.KEY_NONE)，由 NEI 的
        // inputHandler 链（LayoutManager）路由到聚焦 TextField 并触发 onTextChange。
        // Feeds the committed text char-by-char into NEI's key pipeline (mirrors
        // IngameIME), routed by NEI's input-handler chain to the focused
        // TextField with onTextChange fired.
        GuiContainerManager manager = GuiContainerManager.getManager();
        if (manager == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            manager.keyTyped(text.charAt(i), Keyboard.KEY_NONE);
        }
    }

    // 候选窗锚点：NEI TextField 的内部 GuiTextField 由 Mixin 上报光标位置，
    // 默认锚点已覆盖，无需自定义。/ Caret anchor: the inner GuiTextField's caret
    // is reported by the Mixin, the default anchor already applies.

    /**
     * 反射读取 {@code TextField.field}（protected）——NEI 发布 jar 保留该 MCP 字段名，
     * 反射不受 reobf 影响。解析失败（版本差异等）返回 null，激活判定自然落空，
     * 提交落回普通路径。Reads {@code TextField.field} (protected) via reflection —
     * the released NEI jar keeps this MCP field name, so reflection is reobf-safe.
     * On failure (e.g. a version mismatch) returns null, the activation check
     * fails and commits fall back to the normal path.
     *
     * @param textField NEI 文本框
     * @return 内部 GuiTextField；解析失败返回 null
     */
    private static GuiTextField resolveInternalField(TextField textField) {
        try {
            if (textFieldField == null && !fieldLookupDone) {
                textFieldField = TextField.class.getDeclaredField("field");
                textFieldField.setAccessible(true);
                fieldLookupDone = true;
            }
            if (textFieldField != null) {
                return (GuiTextField) textFieldField.get(textField);
            }
        } catch (Throwable t) {
            // 字段解析失败：退化为普通注入，不阻碍 IME 主流程
            // Field resolution failed: degrade to the normal injection path
            fieldLookupDone = true;
            textFieldField = null;
        }
        return null;
    }
}
