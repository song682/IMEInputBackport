package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiRenameWorld;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GuiRenameWorld} 混入：补齐 IME 提交文字绕过 {@code keyTyped} 造成的按钮状态失步。
 * <p>
 * 原版"重命名世界"界面的重命名按钮可用性只在 {@code keyTyped} 中随按键刷新；
 * 而 IME 提交经 {@link GuiTextField#writeText} 直写文本框，不经过 {@code keyTyped}——
 * 清空后改输中文时按钮可用性停在旧值。此处在 {@code updateScreen} 尾部侦测
 * 文本框内容变化，一旦变化即复刻 {@code keyTyped} 中的同款刷新。
 * Mixin for {@link GuiRenameWorld}: closes the button-state gap caused by IME
 * commits bypassing {@code keyTyped}. Vanilla refreshes the rename-button state
 * only inside {@code keyTyped}, but IME text arrives through
 * {@link GuiTextField#writeText} and skips that path — after clearing the field
 * and typing Chinese the button stays stale. This watches the field for content
 * changes at the tail of {@code updateScreen} and replays the same refresh.
 * <p>
 * 说明：采用 TAIL 最小侵入注入，不改动原方法逻辑。
 */
@Mixin(GuiRenameWorld.class)
public abstract class MixinGuiRenameWorld {

    // ------------------------------------------------------------
    //  影子成员（映射到 GuiRenameWorld 内部字段）
    // ------------------------------------------------------------

    /** 世界名称输入框 / The world name text field */
    @Shadow private GuiTextField field_146583_f;

    // ------------------------------------------------------------
    //  文本变化侦测
    // ------------------------------------------------------------

    /** 上一次观测到的名称文本框内容 / Last observed content of the name field */
    @Unique private String imeinput$lastObservedName = null;

    /**
     * 在 updateScreen 结束后比对文本框内容：无变化直接返回；首次观测仅记录
     * 基线（initGui 初始文本无需刷新按钮）；其后一旦侦测到变化，按原版
     * keyTyped 的判定重设重命名按钮可用性。键盘输入路径下 keyTyped 已先行
     * 刷新，此处重算结果幂等。
     * Compares the field's content at the tail of updateScreen: returns on no
     * change; the first sighting only records the baseline; any later change
     * resets the rename-button state with vanilla keyTyped's exact predicate.
     * Idempotent on the keyboard path where keyTyped already refreshed.
     *
     * @param ci 回调信息
     */
    @Inject(method = "updateScreen", at = @At("TAIL"))
    private void imeinput$syncButtonStateOnTextChange(CallbackInfo ci) {
        if (this.field_146583_f == null) {
            return;
        }
        String current = this.field_146583_f.getText();
        if (current.equals(this.imeinput$lastObservedName)) {
            return;
        }
        if (this.imeinput$lastObservedName == null) {
            // 首次观测：仅记录基线 / First sighting: just record the baseline
            this.imeinput$lastObservedName = current;
            return;
        }
        this.imeinput$lastObservedName = current;
        // 按钮列表继承自父类 GuiScreen，混入 AP 无法 @Shadow，经反射工具访问
        // buttonList is inherited from GuiScreen and unshadowable, use the helper
        ButtonListAccess.setFirstButtonEnabled((GuiRenameWorld) (Object) this, current.trim().length() > 0);
    }
}
