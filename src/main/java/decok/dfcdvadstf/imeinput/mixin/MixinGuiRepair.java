package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GuiRepair} 混入：补齐 IME 提交文字绕过 {@code keyTyped} 造成的铁砧命名失步。
 * <p>
 * 原版铁砧命名只在 {@code keyTyped} 中、{@code textboxKeyTyped} 确实改动文本时
 * 调用 {@code func_147090_g()} 把新名字同步给容器并打包发给服务端；而 IME 提交
 * 经 {@link GuiTextField#writeText} 直写文本框，不经过 {@code keyTyped}——输入
 * 中文后名字既不进容器也不会发包，取出物品时命名静默丢失。此类未覆写
 * {@code updateScreen}（继承自 {@code GuiContainer}，混入无法定向注入），
 * 故在本类声明的 {@code drawScreen} 尾部侦测文本框内容变化，一旦变化即
 * 复刻 {@code keyTyped} 的同款同步。
 * Mixin for {@link GuiRepair}: closes the anvil-naming gap caused by IME
 * commits bypassing {@code keyTyped}. Vanilla syncs the new name to the
 * container and sends it to the server via {@code func_147090_g()} only inside
 * {@code keyTyped}, but IME text arrives through {@link GuiTextField#writeText}
 * and skips that path — after typing Chinese the name never reaches the
 * container or the server and is silently lost when the item is taken out.
 * This class does not override {@code updateScreen} (it is inherited from
 * {@code GuiContainer}, which a mixin on this class cannot target), so the
 * change detection sits at the tail of {@code drawScreen} — declared here —
 * and replays the exact sync {@code keyTyped} performs.
 * <p>
 * 说明：采用 TAIL 最小侵入注入，不改动原方法逻辑。
 */
@Mixin(GuiRepair.class)
public abstract class MixinGuiRepair {

    // ------------------------------------------------------------
    //  影子成员（映射到 GuiRepair 内部字段与方法）
    // ------------------------------------------------------------

    /** 铁砧命名输入框 / The anvil name text field */
    @Shadow private GuiTextField field_147091_w;

    /** 原版私有方法：把当前名字同步给容器并向服务端发送命名数据包 */
    /** Vanilla private: push the current name into the container and to the server */
    @Shadow protected abstract void func_147090_g();

    // ------------------------------------------------------------
    //  文本变化侦测
    // ------------------------------------------------------------

    /** 上一次观测到的命名文本框内容 / Last observed content of the name field */
    @Unique private String imeinput$lastObservedName = null;

    /**
     * 在 drawScreen 结束后比对命名文本框内容：无变化直接返回；首次观测仅记录
     * 基线（initGui 时文本框为空且无物品，无需同步）；其后一旦侦测到变化，
     * 调用原版 {@code func_147090_g()} 完成容器同步与发包。键盘输入路径下
     * keyTyped 已先行同步，此处重算结果幂等（同名更新不改变结果）。
     * Compares the name field's content at the tail of drawScreen: returns on
     * no change; the first sighting only records the baseline (the field starts
     * empty with no item); any later change invokes vanilla
     * {@code func_147090_g()} to sync the container and send the packet.
     * Idempotent on the keyboard path where keyTyped already synced.
     *
     * @param mouseX      鼠标 X（原方法参数，未使用）
     * @param mouseY      鼠标 Y（原方法参数，未使用）
     * @param partialTicks 局部刻（原方法参数，未使用）
     * @param ci          回调信息
     */
    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void imeinput$syncItemNameOnTextChange(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (this.field_147091_w == null) {
            return;
        }
        String current = this.field_147091_w.getText();
        if (current.equals(this.imeinput$lastObservedName)) {
            return;
        }
        if (this.imeinput$lastObservedName == null) {
            // 首次观测：仅记录基线 / First sighting: just record the baseline
            this.imeinput$lastObservedName = current;
            return;
        }
        this.imeinput$lastObservedName = current;
        this.func_147090_g();
    }
}
