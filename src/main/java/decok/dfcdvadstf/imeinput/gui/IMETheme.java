package decok.dfcdvadstf.imeinput.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import decok.dfcdvadstf.imeinput.IMEInoutBackport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * IME 组词窗主题系统：统一掌管窗体外观（窗体底、边框、分隔线）与配色。
 * <p>
 * 主题<b>由资源包加载</b>：模组自带默认主题描述文件
 * {@code assets/imeinput/ime_theme.json}，资源包按同路径整体覆盖即可换肤
 * （换风格、换配色、换窗体纹理）；本类注册为资源重载监听器，切资源包 /
 * F3+T 即时生效，文件缺失或解析失败时回退内置默认值并记日志。
 * <p>
 * 两种窗体绘制风格，由主题文件的 {@code style} 字段选择：
 * <ul>
 *   <li>{@code FLAT}（默认）——纯自绘平面风格：外层 1px 边框矩形
 *       套内部实色矩形，零纹理依赖；</li>
 *   <li>{@code TEXTURE}——纹理风格：以九宫格（nine-slice）方式拉伸主题
 *       指定的窗体纹理（默认 {@code imeinput:textures/gui/ime_window.png}，
 *       32×32、四角各 4px 固定、边与中心拉伸）。</li>
 * </ul>
 * 文字/高亮等前景配色两种风格共用（默认深底浅字），本类只暴露取色接口，
 * 具体文字排版仍由 {@link IMEOverlay} 负责。
 * <p>
 * IME composition window theme system: owns the window chrome (fill, border,
 * separator) and the color palette. Themes are <b>resource-pack loaded</b>:
 * the mod ships a default descriptor at {@code assets/imeinput/ime_theme.json}
 * which packs override wholesale to reskin (style, palette, window texture);
 * this class registers as a resource reload listener so pack switches /
 * F3+T apply instantly, falling back to built-in defaults on a missing or
 * broken file. Two window styles selected by the descriptor's {@code style}
 * field: FLAT (default) draws a 1px border rectangle around a solid interior
 * with zero texture dependency; TEXTURE stretches the theme's nine-slice
 * window texture (4px fixed corners, stretched edges/center). Foreground
 * colors are shared by both styles; text layout stays in {@link IMEOverlay}.
 */
public final class IMETheme implements IResourceManagerReloadListener {

    /** 重载监听单例 / Reload-listener singleton */
    private static final IMETheme INSTANCE = new IMETheme();

    /** 主题描述文件位置（资源包按此路径覆盖）/ Theme descriptor location, pack-overridable */
    private static final ResourceLocation THEME_JSON =
            new ResourceLocation("imeinput", "ime_theme.json");

    /** 当前生效主题（重载线程整体发布，渲染只读）/ Active theme, published atomically */
    private static volatile ThemeData theme = ThemeData.defaults();

    private IMETheme() {
    }

    /**
     * 不可变主题数据：一次重载解析出一份整体发布。
     * Immutable theme data: parsed and published as a whole per reload.
     */
    private static final class ThemeData {
        /** true = 九宫格纹理风格；false = 平面自绘 / true = nine-slice, false = flat */
        final boolean textured;
        /** 窗体纹理（TEXTURE 风格用）/ Window texture for the TEXTURE style */
        final ResourceLocation texture;
        /** 纹理边长（像素）/ Texture dimension in pixels */
        final int textureSize;
        /** 九宫格四角固定尺寸 / Fixed nine-slice corner size */
        final int cornerSize;
        /** FLAT 窗体边框色 / FLAT window border */
        final int border;
        /** FLAT 窗体内部底色 / FLAT window interior fill */
        final int background;
        /** 分隔线色 / Separator line */
        final int separator;
        /** 普通文字色 / Normal text */
        final int text;
        /** 聚焦分段文字色 / Focused clause text */
        final int focusedText;
        /** 组字分段下划线色 / Composition block underline */
        final int underline;
        /** 组字内光标竖线色 / In-composition caret bar */
        final int caret;
        /** 选中候选项背景色 / Selected candidate background */
        final int selectedBackground;
        /** 选中候选项文字色 / Selected candidate text */
        final int selectedText;

        ThemeData(boolean textured, ResourceLocation texture, int textureSize, int cornerSize,
                  int border, int background, int separator, int text, int focusedText,
                  int underline, int caret, int selectedBackground, int selectedText) {
            this.textured = textured;
            this.texture = texture;
            this.textureSize = textureSize;
            this.cornerSize = cornerSize;
            this.border = border;
            this.background = background;
            this.separator = separator;
            this.text = text;
            this.focusedText = focusedText;
            this.underline = underline;
            this.caret = caret;
            this.selectedBackground = selectedBackground;
            this.selectedText = selectedText;
        }

        /**
         * 内置默认主题（与随包 ime_theme.json 保持一致）：深底浅字平面风格。
         * Built-in defaults (mirroring the bundled ime_theme.json): dark flat look.
         */
        static ThemeData defaults() {
            return new ThemeData(false,
                    new ResourceLocation("imeinput", "textures/gui/ime_window.png"), 32, 4,
                    0xFF34343C, 0xF0141418, 0xFF3C3C46,
                    0xFFE0E0E0, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFD0D0D0,
                    0xEBB2DAE0, 0xFF202020);
        }
    }

    // ============================================================
    //  资源包加载 / Resource-pack loading
    // ============================================================

    /**
     * 注册资源重载监听（注册即触发一次首载）；由 IMEHandler 客户端初始化时调用。
     * 首载时模组资源可能尚未注入资源管理器，失败会静默回退默认值，
     * 随后的整体 refreshResources 会再次触发本监听完成正式加载。
     * Registers the resource reload listener (registration fires an initial
     * load); called from the client init. The mod's resources may not be in
     * the manager yet on that first call — a silent fallback to defaults
     * covers it, and the full refreshResources that follows reloads properly.
     */
    public static void init() {
        IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).registerReloadListener(INSTANCE);
        }
    }

    /** 资源重载回调：重新解析主题描述文件 / Reload hook: re-parse the descriptor */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        theme = load(manager);
    }

    /**
     * 从资源管理器读取并解析 ime_theme.json；任何异常都回退内置默认主题。
     * Reads and parses ime_theme.json from the resource manager; any failure
     * falls back to the built-in defaults.
     */
    private static ThemeData load(IResourceManager manager) {
        ThemeData def = ThemeData.defaults();
        InputStream in = null;
        try {
            in = manager.getResource(THEME_JSON).getInputStream();
            Reader reader = new InputStreamReader(in, "UTF-8");
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();

            boolean textured = "TEXTURE".equalsIgnoreCase(optString(root, "style", "FLAT"));
            JsonObject window = optObject(root, "window");
            JsonObject colors = optObject(root, "colors");

            ThemeData loaded = new ThemeData(
                    textured,
                    new ResourceLocation(optString(window, "texture",
                            "imeinput:textures/gui/ime_window.png")),
                    optInt(window, "textureSize", def.textureSize),
                    optInt(window, "cornerSize", def.cornerSize),
                    optColor(window, "borderColor", def.border),
                    optColor(window, "backgroundColor", def.background),
                    optColor(colors, "separator", def.separator),
                    optColor(colors, "text", def.text),
                    optColor(colors, "focusedText", def.focusedText),
                    optColor(colors, "underline", def.underline),
                    optColor(colors, "caret", def.caret),
                    optColor(colors, "selectedBackground", def.selectedBackground),
                    optColor(colors, "selectedText", def.selectedText));
            IMEInoutBackport.logger.info("[IME] Theme loaded: style="
                    + (textured ? "TEXTURE" : "FLAT") + " from " + THEME_JSON);
            return loaded;
        } catch (Throwable t) {
            // 首载时模组资源尚未注入属预期路径，仅记 debug；正式重载失败记 warn
            // Missing on the very first load is expected; real failures warrant a warn
            IMEInoutBackport.logger.warn("[IME] Theme descriptor unavailable ("
                    + t + "), using built-in defaults");
            return def;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 取字符串字段，缺失回退默认 / Optional string field */
    private static String optString(JsonObject obj, String key, String def) {
        return obj != null && obj.has(key) ? obj.get(key).getAsString() : def;
    }

    /** 取整数字段，缺失回退默认 / Optional int field */
    private static int optInt(JsonObject obj, String key, int def) {
        return obj != null && obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    /** 取子对象，缺失返回 null / Optional child object */
    private static JsonObject optObject(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? obj.getAsJsonObject(key) : null;
    }

    /**
     * 取颜色字段：接受 "#AARRGGBB" / "AARRGGBB" / "#RRGGBB"（视为不透明）。
     * Optional color field: "#AARRGGBB" / "AARRGGBB" / "#RRGGBB" (opaque).
     */
    private static int optColor(JsonObject obj, String key, int def) {
        String raw = optString(obj, key, null);
        if (raw == null) {
            return def;
        }
        try {
            String hex = raw.startsWith("#") ? raw.substring(1) : raw;
            long value = Long.parseLong(hex, 16);
            if (hex.length() <= 6) {
                value |= 0xFF000000L; // 无 alpha 位视为不透明 / opaque when alpha absent
            }
            return (int) value;
        } catch (NumberFormatException e) {
            IMEInoutBackport.logger.warn("[IME] Bad theme color '" + raw + "' for " + key);
            return def;
        }
    }

    // ============================================================
    //  取色接口（IMEOverlay 消费）/ Palette accessors for IMEOverlay
    // ============================================================

    /** 普通文字色 / Normal text color */
    public static int textColor() {
        return theme.text;
    }

    /** 聚焦分段文字色 / Focused clause text color */
    public static int focusedTextColor() {
        return theme.focusedText;
    }

    /** 组字分段下划线色 / Composition block underline color */
    public static int underlineColor() {
        return theme.underline;
    }

    /** 组字内光标竖线色 / In-composition caret bar color */
    public static int caretColor() {
        return theme.caret;
    }

    /** 选中候选项背景色 / Selected candidate background color */
    public static int selectedBackgroundColor() {
        return theme.selectedBackground;
    }

    /** 选中候选项文字色 / Selected candidate text color */
    public static int selectedTextColor() {
        return theme.selectedText;
    }

    // ============================================================
    //  窗体绘制 / Window chrome drawing
    // ============================================================

    /**
     * 绘制一个组词窗窗体（含边框与底）。FLAT：1px 边框矩形 + 内部实色矩形；
     * TEXTURE：九宫格拉伸主题窗体纹理。
     * Draws one composition window chrome (border + fill). FLAT: a 1px border
     * rectangle around a solid interior; TEXTURE: nine-slice stretch of the
     * theme's window texture.
     *
     * @param x 左上角 GUI X / top-left GUI X
     * @param y 左上角 GUI Y / top-left GUI Y
     * @param w 窗体宽 / window width
     * @param h 窗体高 / window height
     */
    public static void drawWindow(int x, int y, int w, int h) {
        ThemeData t = theme;
        if (t.textured) {
            drawNineSlice(t, x, y, w, h);
        } else {
            Gui.drawRect(x, y, x + w, y + h, t.border);
            Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, t.background);
        }
    }

    /**
     * 绘制两区之间的 1px 内嵌分隔线（左右各缩进 2px，呈"内部分割"观感）。
     * Draws the 1px inset separator between the two sections (2px side inset
     * for an interior-divider look).
     *
     * @param x 窗体左上角 GUI X / window top-left GUI X
     * @param y 分隔线所在 GUI Y / separator GUI Y
     * @param w 窗体宽 / window width
     */
    public static void drawSeparator(int x, int y, int w) {
        Gui.drawRect(x + 2, y, x + w - 2, y + 1, theme.separator);
    }

    /**
     * 九宫格绘制：四角原尺寸、四边单向拉伸、中心双向拉伸。
     * 窗体小于两个角尺寸时按比例夹取，避免角片互相重叠。
     * Nine-slice draw: fixed corners, one-way stretched edges, two-way
     * stretched center. Corner sizes are clamped so tiny windows don't
     * overlap their corner patches.
     */
    private static void drawNineSlice(ThemeData t, int x, int y, int w, int h) {
        int texSize = t.textureSize;
        int cw = Math.min(t.cornerSize, w / 2);
        int ch = Math.min(t.cornerSize, h / 2);

        Minecraft.getMinecraft().getTextureManager().bindTexture(t.texture);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tessellator tes = Tessellator.instance;
        tes.startDrawingQuads();
        // 三行三列：u/v 取样区与目标区一一对应 / 3x3 grid of patches
        int x1 = x + cw, x2 = x + w - cw;
        int y1 = y + ch, y2 = y + h - ch;
        int u1 = cw, u2 = texSize - cw;
        int v1 = ch, v2 = texSize - ch;
        patch(tes, texSize, x, y, x1, y1, 0, 0, u1, v1);                     // 左上 / top-left
        patch(tes, texSize, x1, y, x2, y1, u1, 0, u2, v1);                   // 上边 / top
        patch(tes, texSize, x2, y, x + w, y1, u2, 0, texSize, v1);           // 右上 / top-right
        patch(tes, texSize, x, y1, x1, y2, 0, v1, u1, v2);                   // 左边 / left
        patch(tes, texSize, x1, y1, x2, y2, u1, v1, u2, v2);                 // 中心 / center
        patch(tes, texSize, x2, y1, x + w, y2, u2, v1, texSize, v2);         // 右边 / right
        patch(tes, texSize, x, y2, x1, y + h, 0, v2, u1, texSize);           // 左下 / bottom-left
        patch(tes, texSize, x1, y2, x2, y + h, u1, v2, u2, texSize);         // 下边 / bottom
        patch(tes, texSize, x2, y2, x + w, y + h, u2, v2, texSize, texSize); // 右下 / bottom-right
        tes.draw();

        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * 提交一个纹理片四边形（像素级 UV 自动归一化）。
     * Emits one textured quad; pixel UVs are normalized here.
     */
    private static void patch(Tessellator t, int texSize, int x0, int y0, int x1, int y1,
                              int u0, int v0, int u1, int v1) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        double s = 1.0 / texSize;
        t.addVertexWithUV(x0, y1, 0.0, u0 * s, v1 * s);
        t.addVertexWithUV(x1, y1, 0.0, u1 * s, v1 * s);
        t.addVertexWithUV(x1, y0, 0.0, u1 * s, v0 * s);
        t.addVertexWithUV(x0, y0, 0.0, u0 * s, v0 * s);
    }
}
