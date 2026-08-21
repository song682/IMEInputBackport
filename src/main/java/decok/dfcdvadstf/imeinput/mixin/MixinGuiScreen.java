package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link GuiScreen} 调用器混入：对外暴露受保护的 {@code keyTyped}。
 * <p>
 * IME 提交文字采用"模拟输入"策略——逐字符调用屏幕的 {@code keyTyped}
 * （keyCode 取 {@code Keyboard.KEY_NONE}），完整复刻真实按键路径：
 * 文本框插入与屏幕自身的派生逻辑（创建世界的文件夹名预览、按钮可用性、
 * 铁砧命名发包等）随每次注入自然刷新，无需为每个界面单独打补丁。
 * {@code keyTyped} 是受保护方法，包外的 {@code IMEHandler} 无法直接调用，
 * 故经本调用器桥接。
 * Invoker mixin for {@link GuiScreen}: exposes the protected {@code keyTyped}.
 * IME commits use a simulated-typing strategy — each committed character is fed
 * through the screen's {@code keyTyped} with {@code Keyboard.KEY_NONE}, fully
 * mirroring a real keystroke: the field insertion and the screen's own derived
 * logic (the create-world folder preview, button states, the anvil rename
 * packet, ...) all refresh naturally per injection, so no per-screen patching
 * is needed. {@code keyTyped} is protected and unreachable from the
 * out-of-package {@code IMEHandler}, hence this invoker bridge.
 */
@Mixin(GuiScreen.class)
public interface MixinGuiScreen {

    /**
     * 调用原版 {@code keyTyped}，模拟一次字符输入。
     * Invokes the vanilla {@code keyTyped}, simulating one typed character.
     *
     * @param typedChar 输入字符 / The typed character
     * @param keyCode   键码（模拟输入固定用 {@code Keyboard.KEY_NONE}，
     *                  避免触发 Enter 提交、Esc 关屏等特殊分支）
     *                  Key code (simulated typing always passes
     *                  {@code Keyboard.KEY_NONE} to avoid special branches
     *                  such as Enter-to-submit or Esc-to-close)
     */
    @Invoker("keyTyped")
    void imeinput$keyTyped(char typedChar, int keyCode);
}
