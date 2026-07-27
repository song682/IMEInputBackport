package decok.dfcdvadstf.imeinput.mixin;

import decok.dfcdvadstf.imeinput.IMEHandler;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GuiTextField} 混入：
 * <ul>
 *   <li>追踪当前聚焦的文本框，供 {@link IMEHandler} 决定 IME 开关与写入目标</li>
 *   <li>在文本框内联渲染 IME 组字预览（带下划线），弥补子类化方案下组字串不自动内联显示的问题</li>
 * </ul>
 * 说明：采用 HEAD/TAIL 最小侵入注入，不改动原方法逻辑。
 */
@Mixin(GuiTextField.class)
public abstract class MixinGuiTextField {

    // ------------------------------------------------------------
    //  影子成员（映射到 GuiTextField 内部字段/方法）
    // ------------------------------------------------------------

    @Shadow @Final private FontRenderer field_146211_a;
    @Shadow private String text;
    @Shadow private boolean enableBackgroundDrawing;
    @Shadow private int lineScrollOffset;
    @Shadow private int cursorPosition;
    @Shadow private int enabledColor;
    @Shadow public int xPosition;
    @Shadow public int yPosition;
    @Shadow public int height;

    @Shadow public abstract boolean isFocused();

    @Shadow public abstract int getWidth();

    // ------------------------------------------------------------
    //  焦点追踪
    // ------------------------------------------------------------

    /**
     * 在 setFocused 结束后同步聚焦实例到 {@link IMEHandler}。
     *
     * @param focused 目标焦点状态
     * @param ci      回调信息
     */
    @Inject(method = "setFocused", at = @At("TAIL"))
    private void imeinput$onSetFocused(boolean focused, CallbackInfo ci) {
        GuiTextField self = (GuiTextField) (Object) this;
        if (focused) {
            IMEHandler.setFocusedField(self);
        } else {
            IMEHandler.clearFocusedField(self);
        }
    }

    // ------------------------------------------------------------
    //  组字预览渲染
    // ------------------------------------------------------------

    /**
     * 在 drawTextBox 结束后，于光标处叠加渲染 IME 组字预览串与下划线。
     * 仅对聚焦文本框且存在组字串时渲染；任何异常都被吞掉以确保不影响原渲染。
     *
     * @param ci 回调信息
     */
    @Inject(method = "drawTextBox", at = @At("TAIL"))
    private void imeinput$onDrawTextBox(CallbackInfo ci) {
        if (!this.isFocused()) {
            return;
        }
        String comp = IMEHandler.getCompositionText();
        if (comp == null || comp.isEmpty()) {
            return;
        }
        try {
            FontRenderer fr = this.field_146211_a;
            if (fr == null || this.text == null) {
                return;
            }

            int color = this.enabledColor;
            int x0 = this.enableBackgroundDrawing ? this.xPosition + 4 : this.xPosition;
            int y0 = this.enableBackgroundDrawing ? this.yPosition + (this.height - 8) / 2 : this.yPosition;

            // 计算光标像素位置（与原 drawTextBox 逻辑保持一致）
            String visible = fr.trimStringToWidth(this.text.substring(this.lineScrollOffset), this.getWidth());
            int rel = this.cursorPosition - this.lineScrollOffset;
            if (rel < 0) rel = 0;
            if (rel > visible.length()) rel = visible.length();
            int caretX = x0 + fr.getStringWidth(visible.substring(0, rel));

            // 渲染组字预览与下划线
            fr.drawStringWithShadow(comp, caretX, y0, color);
            int w = fr.getStringWidth(comp);
            Gui.drawRect(caretX, y0 + fr.FONT_HEIGHT, caretX + w, y0 + fr.FONT_HEIGHT + 1, 0xFFFFFFFF);
        } catch (Throwable ignored) {
            // 组字预览渲染失败不应影响文本框本体
        }
    }
}
