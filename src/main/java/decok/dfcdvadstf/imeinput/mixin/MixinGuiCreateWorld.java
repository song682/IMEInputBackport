package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GuiCreateWorld} 混入：补齐 IME 提交文字绕过 {@code keyTyped} 造成的派生状态失步。
 * <p>
 * 原版"创建世界"界面的存档文件夹名预览（"保存在: …"提示行）与创建按钮可用性，
 * 只在 {@code keyTyped} 中随按键事件刷新；而 IME 提交经
 * {@code IMEHandler} 走 {@link GuiTextField#writeText} 直写文本框，不经过
 * {@code keyTyped}——导致输入中文后预览行与按钮状态停在旧值，玩家看不到
 * 世界将按所输名称保存的提示。此处在 {@code updateScreen} 尾部侦测名称
 * 文本框内容变化，一旦变化即复刻 {@code keyTyped} 末尾的同款刷新逻辑。
 * Mixin for {@link GuiCreateWorld}: closes the derived-state gap caused by IME
 * commits bypassing {@code keyTyped}. Vanilla recomputes the save-folder preview
 * (the "Save to: …" hint line) and the create-button state only inside
 * {@code keyTyped}, but IME text arrives through {@link GuiTextField#writeText}
 * and skips that path — so after typing Chinese the preview and button stay
 * stale. This watches the name field for content changes at the tail of
 * {@code updateScreen} and re-runs the exact refresh logic {@code keyTyped}
 * applies.
 * <p>
 * 说明：采用 TAIL 最小侵入注入，不改动原方法逻辑。
 */
@Mixin(GuiCreateWorld.class)
public abstract class MixinGuiCreateWorld {

    // ------------------------------------------------------------
    //  影子成员（映射到 GuiCreateWorld 内部字段与方法）
    // ------------------------------------------------------------

    /** 世界名称输入框 / The world name text field */
    @Shadow private GuiTextField field_146333_g;
    /** 世界名称备份缓存（原版 keyTyped 中同步）/ Name copy cached by vanilla keyTyped */
    @Shadow private String field_146330_J;

    /** 原版私有方法：由当前名称重算存档文件夹名预览 / Vanilla private: recompute folder preview */
    @Shadow protected abstract void func_146314_g();

    // ------------------------------------------------------------
    //  文本变化侦测
    // ------------------------------------------------------------

    /** 上一次观测到的名称文本框内容 / Last observed content of the name field */
    @Unique private String imeinput$lastObservedName = null;

    /**
     * 在 updateScreen 结束后比对名称文本框内容：无变化直接返回；
     * 首次观测仅记录基线（initGui 已做过一轮刷新）；其后一旦侦测到变化，
     * 即按原版 keyTyped 的顺序复刻刷新——同步名称备份、重设创建按钮
     * 可用性、重算存档文件夹名预览。键盘输入路径下 keyTyped 已先行刷新，
     * 此处重算结果幂等，仅多一次无副作用的比对。
     * Compares the name field's content at the tail of updateScreen: returns on
     * no change; the first sighting only records the baseline (initGui already
     * refreshed once); any later change replays vanilla keyTyped's refresh in
     * order — sync the cached name, update the create-button state, recompute
     * the folder preview. On the keyboard path keyTyped already refreshed, so
     * the replay is idempotent.
     *
     * @param ci 回调信息
     */
    @Inject(method = "updateScreen", at = @At("TAIL"))
    private void imeinput$syncDerivedStateOnTextChange(CallbackInfo ci) {
        if (this.field_146333_g == null) {
            return;
        }
        String current = this.field_146333_g.getText();
        if (current.equals(this.imeinput$lastObservedName)) {
            return;
        }
        if (this.imeinput$lastObservedName == null) {
            // 首次观测：initGui 已刷新过一轮，只记录基线
            // First sighting: initGui already refreshed, just record the baseline
            this.imeinput$lastObservedName = current;
            return;
        }
        this.imeinput$lastObservedName = current;
        // 复刻原版 keyTyped 的刷新顺序：备份名称 → 创建按钮可用性 → 重算文件夹名预览
        // Mirror vanilla keyTyped's order: cache name, set create-button state,
        // then recompute the folder name preview
        this.field_146330_J = current;
        // 按钮列表继承自父类 GuiScreen，混入 AP 无法 @Shadow，经反射工具访问
        // buttonList is inherited from GuiScreen and unshadowable, use the helper
        ButtonListAccess.setFirstButtonEnabled((GuiCreateWorld) (Object) this, current.length() > 0);
        this.func_146314_g();
    }
}
