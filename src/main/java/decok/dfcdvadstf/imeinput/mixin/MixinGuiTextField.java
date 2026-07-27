package decok.dfcdvadstf.imeinput.mixin;

import decok.dfcdvadstf.imeinput.IMEHandler;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import org.jnilib.imeinput.IMEBridge;
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
     * <p>
     * 对齐高版本 PreeditEvent 渲染语义：逐分段（block）绘制，聚焦段加粗下划线高亮，
     * 并在组字串内部绘制 caret；同时上报 caret 的 GUI 坐标供候选窗跟随定位。
     * Per-block rendering with focused-block highlight and in-composition caret,
     * matching the modern PreeditEvent presentation; also reports the caret GUI
     * position so the candidate window can follow it.
     *
     * @param ci 回调信息
     */
    @Inject(method = "drawTextBox", at = @At("TAIL"))
    private void imeinput$onDrawTextBox(CallbackInfo ci) {
        if (!this.isFocused()) {
            return;
        }
        IMEBridge.CompositionInfo info = IMEHandler.getCompositionInfo();
        if (info == null) {
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

            // 逐分段渲染：聚焦段高亮色 + 加粗下划线，其余段普通色 + 细下划线；
            // caret 像素位置在循环内累积计算，以包含分段间缝隙的偏移
            // Per-block: focused block gets highlight color + thick underline;
            // caret pixel position is accumulated in-loop to include block gaps
            int underlineY = y0 + fr.FONT_HEIGHT;
            int bx = caretX;
            int charOffset = 0;
            int compCaretX = caretX;
            for (int i = 0; i < info.blocks.length; i++) {
                String block = info.blocks[i];
                if (block.isEmpty()) {
                    continue;
                }
                // caret 落在本段内（含段尾）时定位到段内像素偏移
                int caretInBlock = info.caretPosition - charOffset;
                if (caretInBlock >= 0 && caretInBlock <= block.length()) {
                    compCaretX = bx + fr.getStringWidth(block.substring(0, caretInBlock));
                }
                boolean focused = i == info.focusedBlock;
                fr.drawStringWithShadow(block, bx, y0, focused ? 0xFFFFFF : color);
                int bw = fr.getStringWidth(block);
                int thickness = focused ? 2 : 1;
                Gui.drawRect(bx, underlineY, bx + bw, underlineY + thickness, 0xFFFFFFFF);
                // 分段间留 1px 缝隙，让段落边界可辨 / 1px gap between block underlines
                bx += bw + (i < info.blocks.length - 1 ? 1 : 0);
                charOffset += block.length();
            }

            // 组字串内部的 caret：竖线绘在 caretPosition 对应像素处
            // In-composition caret: vertical bar at caretPosition
            Gui.drawRect(compCaretX, y0 - 1, compCaretX + 1, y0 + fr.FONT_HEIGHT, 0xFFD0D0D0);

            // 上报 caret GUI 坐标（下划线下沿），供候选窗跟随光标定位
            // Report caret GUI position (below underline) for candidate placement
            IMEHandler.reportCaretPos(compCaretX, underlineY + 2);
        } catch (Throwable ignored) {
            // 组字预览渲染失败不应影响文本框本体
        }
    }
}
