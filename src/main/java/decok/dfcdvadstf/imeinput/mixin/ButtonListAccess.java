package decok.dfcdvadstf.imeinput.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.lang.reflect.Field;
import java.util.List;

/**
 * {@link GuiScreen} 按钮列表反射访问工具。
 * <p>
 * 混入注解处理器无法解析继承自父类的 {@code @Shadow} 字段
 * （对 {@code GuiScreen.buttonList} 报 "Cannot find target"），
 * 供 {@link MixinGuiCreateWorld} / {@link MixinGuiRenameWorld} 等
 * 子类混入经反射读写按钮列表。字段句柄首次解析后缓存，
 * 按 MCP 名与 SRG 名双名查找以同时覆盖开发与生产环境。
 * Reflective access to {@link GuiScreen}'s button list. The mixin annotation
 * processor cannot resolve {@code @Shadow} fields inherited from a superclass
 * (it reports "Cannot find target" for {@code GuiScreen.buttonList}), so
 * subclass mixins like {@link MixinGuiCreateWorld} / {@link MixinGuiRenameWorld}
 * read the list through this helper instead. The field handle is resolved once
 * and cached, looked up by both the MCP and the SRG name to cover the dev and
 * production environments alike.
 */
final class ButtonListAccess {

    /** 缓存的按钮列表字段句柄；解析失败记 null 永不再试 / Cached handle; null after a failed lookup */
    private static Field buttonListField = null;

    private ButtonListAccess() {
    }

    /**
     * 设置指定界面第一个按钮（id 0，通常为确认/创建/重命名按钮）的可用性。
     * 反射不可用或按钮列表为空时静默放弃——按钮可用性仅影响交互便利，
     * 不应阻断调用方的核心刷新逻辑。
     * Sets the enabled state of the screen's first button (id 0, usually the
     * confirm/create/rename button). Silently bails when reflection is
     * unavailable or the list is empty — button state is a convenience and
     * must never break the caller's core refresh logic.
     *
     * @param screen  目标界面 / Target screen
     * @param enabled 目标可用性 / Target enabled state
     */
    static void setFirstButtonEnabled(GuiScreen screen, boolean enabled) {
        try {
            Field listField = buttonListField;
            if (listField == null) {
                // 双名查找：MCP 名（开发环境）与 SRG 名（生产环境）
                // Both names: MCP (dev environment) and SRG (production)
                for (String name : new String[] {"buttonList", "field_146292_n"}) {
                    try {
                        listField = GuiScreen.class.getDeclaredField(name);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (listField == null) {
                    return;
                }
                listField.setAccessible(true);
                buttonListField = listField;
            }
            List<?> buttons = (List<?>) listField.get(screen);
            if (buttons.isEmpty()) {
                return;
            }
            ((GuiButton) buttons.get(0)).enabled = enabled;
        } catch (Throwable ignored) {
            // 反射失败不影响调用方核心刷新 / Reflection failure must not break the caller
        }
    }
}
