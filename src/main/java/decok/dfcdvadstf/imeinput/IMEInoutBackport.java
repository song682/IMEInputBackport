package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import decok.dfcdvadstf.imeinput.compat.NEICompat;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jnilib.imeinput.IMEBridge;

// CatFrame is a hard dependency: the self-drawn IME composition overlay plugs into its Overlay system.
// CatFrame 为硬前置：自绘 IME 组词窗接入其 Overlay 体系。
// NEI (official or GTNH fork) is optional: commit injection routes through its key pipeline.
// NEI（官方版或 GTNH 非官方版）为可选：提交注入经其按键管线路由。
@Mod(modid = Tags.MODID, name = Tags.NAME, version = Tags.VERSION, useMetadata = true, dependencies = "required-after:catframe@[0.5.8,);after:NotEnoughItems")
public class IMEInoutBackport {
    public static Logger logger = LogManager.getLogger(Tags.NAME);

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Client-only: install IME backend & callbacks.
        // 仅客户端：安装 IME 后端与回调（避免在专用服务端加载客户端类）。
        if (event.getSide().isClient()) {
            Configuration config = new Configuration(event.getSuggestedConfigurationFile());
            int mode;
            boolean nativeBar;
            try {
                config.load();
                mode = loadBackendConfig(config);
                nativeBar = loadCandidateBarConfig(config);
            } finally {
                if (config.hasChanged()) {
                    config.save();
                }
            }
            IMEHandler.init(mode, nativeBar);
            // Register built-in IME commit targets (NEI search/quantity box).
            // 注册内置 IME 提交目标（NEI 搜索框/数量框；未装 NEI 时为空操作）。
            NEICompat.register();
        }
        if (Loader.isModLoaded("ingameime"))
            throw new RuntimeException("IngameIME is detected, the duplicate IME backend is not allowed.");
    }

    /**
     * 从 Forge 配置（config/imeinput.cfg）读取 IME 后端模式。
     * {@code general.backend = AUTO | TSF | IMM32 | IBUS}，默认 AUTO（按平台
     * 自动匹配：Windows 先试 TSF 失败降级 IMM32，Linux 恒走 IBUS）；
     * TSF / IMM32 仅 Windows 生效，IBUS 仅 Linux 生效，跨平台取值被忽略
     * （等同 AUTO）；非法取值回退 AUTO 并记日志。
     * Reads the IME backend mode from the Forge config (config/imeinput.cfg):
     * {@code general.backend = AUTO | TSF | IMM32 | IBUS}, defaulting to AUTO
     * (platform-matched: Windows tries TSF then IMM32, Linux always IBus).
     * TSF/IMM32 apply to Windows only and IBUS to Linux only — a value for the
     * other platform degrades to AUTO; invalid values fall back with a log.
     *
     * @param config 已加载的 Forge 配置（调用方负责 load/save）
     * @return {@link IMEBridge#BACKEND_AUTO} / {@code BACKEND_TSF} /
     *         {@code BACKEND_IMM32} / {@code BACKEND_IBUS}
     */
    private int loadBackendConfig(Configuration config) {
        String value = config.getString("backend", Configuration.CATEGORY_GENERAL, "AUTO",
                "IME backend: AUTO = match the platform (Windows: try TSF then fall back "
                        + "to IMM32; Linux: IBus); TSF / IMM32 = force that backend (Windows "
                        + "only); IBUS = force IBus (Linux only). "
                        + "IME 后端：AUTO = 按平台自动匹配（Windows 先试 TSF 失败降级 IMM32，"
                        + "Linux 走 IBus）；TSF / IMM32 = 强制指定后端（仅 Windows）；"
                        + "IBUS = 强制 IBus（仅 Linux）。",
                new String[] { "AUTO", "TSF", "IMM32", "IBUS" });
        if ("TSF".equalsIgnoreCase(value)) {
            return IMEBridge.BACKEND_TSF;
        }
        if ("IMM32".equalsIgnoreCase(value)) {
            return IMEBridge.BACKEND_IMM32;
        }
        if ("IBUS".equalsIgnoreCase(value)) {
            return IMEBridge.BACKEND_IBUS;
        }
        if (!"AUTO".equalsIgnoreCase(value)) {
            logger.warn("[IME] Unknown backend '" + value + "' in imeinput.cfg, using AUTO");
        }
        return IMEBridge.BACKEND_AUTO;
    }

    /**
     * 从 Forge 配置读取候选条外观开关。
     * {@code general.candidateNativeBar = true | false}，默认 true：
     * 候选窗由系统/输入法自身绘制；false 时隐藏原生候选窗，
     * 改由模组的 IMEOverlay 自绘候选框（与 PreEdit 框上下堆叠）。
     * 启动时读取一次，修改后需重启游戏生效。
     * Reads the candidate bar appearance switch:
     * {@code general.candidateNativeBar = true | false}, default true (the
     * OS/IME draws its own candidate window); false hides the native window
     * and the mod's IMEOverlay self-draws the candidate box instead (stacked
     * below the PreEdit box). Read once at startup; changes need a restart.
     *
     * @param config 已加载的 Forge 配置（调用方负责 load/save）
     * @return true = 原生候选条；false = 自绘候选框
     */
    private boolean loadCandidateBarConfig(Configuration config) {
        return config.getBoolean("candidateNativeBar", Configuration.CATEGORY_GENERAL, true,
                "Candidate bar appearance: true = the OS/IME draws its native candidate window; "
                        + "false = hide it and let the mod self-draw the candidate box. "
                        + "Needs a game restart. "
                        + "候选条外观：true = 系统/输入法原生候选窗；false = 隐藏原生候选窗，"
                        + "由模组自绘候选框。修改后需重启游戏。");
    }
}