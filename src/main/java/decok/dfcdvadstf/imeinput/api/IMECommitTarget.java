package decok.dfcdvadstf.imeinput.api;

/**
 * IME 提交目标接口：任何模组/前置的文本输入控件（搜索框、数量框、EditBox 等）
 * 实现本接口并通过 {@link IMEInputAPI#register(IMECommitTarget)} 注册后，
 * 即可接入 IMEInputBackport 的输入管线：
 * <ul>
 * <li>{@link #shouldReceiveInput()} 驱动 IME 开关与提交路由——返回 true 时该控件获得
 * 输入法输入，本模组每 tick 查询一次</li>
 * <li>{@link #commitText(String)} 接收 IME 提交的完整文本，实现方自行决定
 * 逐字符注入按键管线或整体插入，并触发自身的刷新逻辑（如搜索更新）</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()} 提供候选窗锚点（GUI 逻辑坐标）；
 * 返回 -1 时回退到默认锚点（Mixin 上报的光标位置或聚焦文本框左下角）</li>
 * </ul>
 * 接入指南：让控件的聚焦判定驱动 {@link #shouldReceiveInput()}；{@link #commitText(String)}
 * 建议复用控件自身的按键/粘贴处理路径（与用户手动粘贴行为一致），以保证
 * 文本校验、长度限制与刷新回调全部生效。非 {@code GuiTextField} 体系的控件
 * （如 CatFrame 的 {@code AbstractEditBox}）通过本接口获得与原生文本框同等的
 * IME 支持——焦点无需 Mixin 追踪，候选窗锚点由 {@code getCaretX/Y} 提供。
 * <p>
 * IME commit target: any mod/predecessor text input control (search box,
 * quantity
 * box, EditBox, ...) can hook into the IMEInputBackport input pipeline by
 * implementing this interface and registering it via
 * {@link IMEInputAPI#register(IMECommitTarget)}:
 * <ul>
 * <li>{@link #shouldReceiveInput()} drives the IME on/off switch and commit
 * routing —
 * when
 * it returns true the control receives IME input; polled once per client
 * tick</li>
 * <li>{@link #commitText(String)} receives the committed text as a whole; the
 * implementor decides between char-by-char key-pipeline injection or a bulk
 * insert, and must trigger its own refresh logic (e.g. search update)</li>
 * <li>{@link #getCaretX()} / {@link #getCaretY()} supply the candidate-window
 * anchor in GUI coordinates; -1 falls back to the default anchor (Mixin
 * reported caret or the focused field's bottom-left corner)</li>
 * </ul>
 * Integration guide: drive {@link #shouldReceiveInput()} from the control's own
 * focus
 * state;
 * implement {@link #commitText(String)} by reusing the control's key/paste
 * handling
 * path (same behaviour as a manual paste) so validation, length limits and
 * refresh
 * callbacks all apply. Controls outside the {@code GuiTextField} family (e.g.
 * CatFrame's {@code AbstractEditBox}) get the same IME support as vanilla text
 * fields — focus needs no Mixin tracking, and the candidate anchor comes from
 * {@code getCaretX/Y}.
 */
public interface IMECommitTarget {

    /**
     * 该目标当前是否应接收 IME 提交（通常等价于控件自身聚焦）。
     * 返回 true 时本模组启用 IME 并把提交路由到 {@link #commitText(String)}；
     * 同一时刻多个目标都返回 true 时，先注册者优先。
     * Whether this target should currently receive IME commits (normally equal to
     * the control's own focus state). When true, the mod enables the IME and
     * routes commits to {@link #commitText(String)}; if several targets are active
     * at once, the first registered one wins.
     *
     * @return true = 激活，接收 IME 输入
     */
    boolean shouldReceiveInput();

    /**
     * 接收 IME 提交的完整文本。实现方应把文本写入自身控件（逐字符注入按键
     * 管线或整体插入均可），并触发与手动输入一致的刷新逻辑。
     * Receives the full committed text. The implementor should write it into its
     * own control (char-by-char key injection or bulk insert) and fire the same
     * refresh logic as manual input.
     *
     * @param text 提交文本（非空）
     */
    void commitText(String text);

    /**
     * 候选窗锚点 X（GUI 逻辑坐标，光标处）；-1 表示未提供，回退默认锚点。
     * Candidate-window anchor X in GUI coordinates (at the caret); -1 = not
     * provided, fall back to the default anchor.
     *
     * @return 锚点 GUI X；-1 = 回退默认
     */
    default int getCaretX() {
        return -1;
    }

    /**
     * 候选窗锚点 Y（GUI 逻辑坐标，光标下沿）；-1 表示未提供，回退默认锚点。
     * Candidate-window anchor Y in GUI coordinates (below the caret); -1 = not
     * provided, fall back to the default anchor.
     *
     * @return 锚点 GUI Y；-1 = 回退默认
     */
    default int getCaretY() {
        return -1;
    }

    /**
     * 当前是否应强制禁用 IME（即使存在聚焦文本框或激活目标）。
     * 供"全屏治理型"目标使用——这类界面的输入框体系不走本接口的提交路由
     * （如 JourneyMap 全屏地图内部隐藏着永远聚焦的 vanilla 文本框，默认焦点
     * 路径会误激活 IME），需要在界面显示期间整体关掉 IME；普通提交目标
     * 保持默认 false，不参与抑制。每 tick 与 {@link #shouldReceiveInput()} 一起被查询。
     * Whether the IME should be forcibly disabled right now (even with a
     * focused text field or an active target). For "fullscreen-governing"
     * targets whose input controls never use this interface's commit routing —
     * e.g. the JourneyMap fullscreen map hosts an always-focused vanilla text
     * field inside a hidden chat widget, which would spuriously enable the IME
     * via the default focus path — the IME must be switched off for the whole
     * screen; ordinary commit targets keep the default false and never block.
     * Polled once per tick alongside {@link #shouldReceiveInput()}.
     *
     * @return true = 强制禁用 IME1
     */
    default boolean isImeBlocked() {
        return false;
    }
}
