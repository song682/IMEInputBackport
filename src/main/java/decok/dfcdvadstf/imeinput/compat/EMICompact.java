package decok.dfcdvadstf.imeinput.compat;

import cpw.mods.fml.common.Loader;
import decok.dfcdvadstf.imeinput.api.IMECommitTarget;
import decok.dfcdvadstf.imeinput.api.IMEInputAPI;
import dev.emi.emi.screen.EmiScreenBase;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.widget.EmiSearchWidget;

/**
 * EMI（RetroEMI Forge 移植版）兼容层——{@link IMECommitTarget} 的内置实现之一，
 * 与 {@link NEICompact} 平级：EMI 与 NEI 同为配方查看器，运行时互斥，注册哪个
 * 取决于 mods 目录实际加载的配方查看器。
 * <p>
 * 提交管线与 NEI 版同构：IME 提交文本逐字符走 {@code EmiScreenManager.search}
 * （EMI 搜索框，{@code public static} 字段）的 {@code charTyped} 字符管线——
 * shim {@code TextFieldWidget} 的 {@code write} → {@code onChanged} →
 * {@code EmiSearch.search} 刷新搜索——与手动按键路径完全一致，搜索刷新、
 * 256 长度限制、格式符过滤等全部生效；直接 {@code setText} 整体替换则会绕过
 * 长度截断并按整体写入（行为与逐字键入不完全一致），故不采用。
 * <p>
 * 激活判定与 NEI 版同样严格：{@link #shouldReceiveInput()} 要求当前屏幕存在 EMI 界面
 * 叠加（{@link EmiScreenBase#getCurrent()} 非空，覆盖容器屏幕与 EMI 配方浏览
 * 屏幕，两者都会把按键路由给搜索框），且搜索框处于可输入状态——EMI 搜索框
 * 自管理焦点（{@code EmiSearchWidget.isFocused}，点击搜索框外自动取消，容器
 * {@code initGui} 时强制复位），{@code TextFieldWidget.isActive()}（可见 + 聚焦
 * + 可编辑）正是 EMI 内部判定"搜索框正接收输入"的信号。无需求助 Mixin 追踪：
 * EMI 搜索框不是 vanilla {@code GuiTextField}，不与之争抢默认注入路径，且 EMI
 * 自身的 {@code hasFocusedTextField} 已保证 mod 文本框聚焦时不拦截按键。
 * <p>
 * 类加载安全：静态初始化不触碰任何 {@code dev.emi} 类；所有 EMI 访问都发生在
 * {@link #isLoaded()} 之后（register 前提），未安装 EMI 时不会触发 EMI 类加载。
 * <p>
 * EMI (RetroEMI Forge port) compatibility layer — a built-in
 * {@link IMECommitTarget} implementation on par with {@link NEICompact}: EMI
 * and
 * NEI are both recipe viewers, mutually exclusive at runtime, and which one is
 * registered depends on what the mods folder actually loaded.
 * <p>
 * The commit pipeline mirrors the NEI one: committed text is fed char-by-char
 * into {@code EmiScreenManager.search}'s {@code charTyped} pipeline (the EMI
 * search box, a {@code public static} field) — the shim {@code TextFieldWidget}
 * {@code write} → {@code onChanged} → {@code EmiSearch.search} refresh — the
 * exact same path as manual typing, so search refresh, the 256-char limit and
 * the format-code stripping all apply; a bulk {@code setText} would bypass the
 * length truncation and write the whole string at once (not identical to typed
 * input), hence not used.
 * <p>
 * Activation is as strict as the NEI one: {@link #shouldReceiveInput()}
 * requires the
 * current screen to host EMI widgets ({@link EmiScreenBase#getCurrent()} not
 * empty — covers container screens and the EMI recipe browser, both of which
 * route keys to the search box) and the search box to be input-ready. The EMI
 * search box manages its own focus ({@code EmiSearchWidget.isFocused}, cleared
 * on clicking elsewhere and force-reset in container {@code initGui}), and
 * {@code TextFieldWidget.isActive()} (visible + focused + editable) is exactly
 * the signal EMI itself uses for "the search box receives input". No Mixin
 * tracking needed: the EMI search box is not a vanilla {@code GuiTextField},
 * so it never contends with the default injection path, and EMI's own
 * {@code hasFocusedTextField} already keeps it from stealing keys while a mod
 * text field is focused.
 * <p>
 * Class-load safety: the static initializer touches no {@code dev.emi} class;
 * all EMI access happens after {@link #isLoaded()} (the register prerequisite),
 * so no EMI class is loaded when EMI is absent.
 */
public final class EMICompact implements IMECommitTarget {

    /** 单例：注册进 {@link IMEInputAPI} 的实例 */
    private static final EMICompact INSTANCE = new EMICompact();

    private EMICompact() {
    }

    /**
     * EMI 已加载时注册为 IME 提交目标（客户端初始化调用一次）。
     * Registers as an IME commit target when EMI is loaded (call once at client
     * init).
     */
    public static void register() {
        if (isLoaded()) {
            IMEInputAPI.register(INSTANCE);
        }
    }

    /**
     * EMI 是否已加载（modid = {@code emi}，RetroEMI Forge 移植版）。
     * Whether EMI is loaded (modid {@code emi}, the RetroEMI Forge port).
     *
     * @return true = 已加载
     */
    public static boolean isLoaded() {
        return Loader.isModLoaded("emi");
    }

    @Override
    public boolean shouldReceiveInput() {
        // 当前屏幕无 EMI 叠加（非容器/非配方浏览屏幕）：搜索框不在屏上，
        // 落回普通注入路径 / Not on an EMI-hosted screen (non-container /
        // non-recipe-browser): the search box is not present, fall back
        if (EmiScreenBase.getCurrent().isEmpty()) {
            return false;
        }
        // 搜索框自身判定（可见 + 聚焦 + 可编辑）：与 EMI 内部"正接收输入"
        // 一致；点击搜索框外或容器切换时 EMI 自动取消焦点，无残留误判
        // The search box's own input-ready check (visible + focused + editable),
        // matching EMI's internal signal; focus is cleared by EMI itself on
        // clicking elsewhere or on container switch, so no stale-focus hazard
        return EmiScreenManager.search.isActive();
    }

    @Override
    public void commitText(String text) {
        // 把 IME 提交文本逐字符注入 EMI 搜索框字符管线（对齐 NEI 版按键注入
        // 的思路）：charTyped → write → onChanged → EmiSearch.search 后台刷新，
        // 与用户手动键入行为一致。modifiers 传 0：IME 提交无修饰键语义。
        // Feeds the committed text char-by-char into the EMI search box's char
        // pipeline (same idea as the NEI key injection): charTyped → write →
        // onChanged → EmiSearch.search background refresh, identical to manual
        // typing. Modifiers are 0: an IME commit carries no modifier meaning.
        for (int i = 0; i < text.length(); i++) {
            EmiScreenManager.search.charTyped(text.charAt(i), 0);
        }
    }

    @Override
    public int getCaretX() {
        // 候选窗锚点 X：搜索框内光标处字符的 GUI 逻辑坐标（getCharacterX 按
        // 已输入文本宽度计算，中文宽度正确） / Anchor X: the caret character's
        // GUI coordinate inside the search box (width of the typed prefix,
        // CJK-aware via FontRenderer)
        EmiSearchWidget search = EmiScreenManager.search;
        return search.getCharacterX(search.getCursor());
    }

    @Override
    public int getCaretY() {
        // 候选窗锚点 Y：搜索框下沿（光标下一行） / Anchor Y: just below the
        // search box's bottom edge (the caret's next line)
        EmiSearchWidget search = EmiScreenManager.search;
        return search.y + search.getHeight() + 2;
    }
}
