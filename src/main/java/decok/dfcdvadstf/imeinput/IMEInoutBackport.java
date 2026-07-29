package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jnilib.imeinput.IMEBridge;

// CatFrame is a hard dependency: the self-drawn IME composition overlay plugs into its Overlay system.
// CatFrame 为硬前置：自绘 IME 组词窗接入其 Overlay 体系。
@Mod(modid = Tags.MODID, name = Tags.NAME, version = Tags.VERSION, useMetadata = true,
        dependencies = "required-after:catframe")
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
        }
    }

    /**
     * 从 Forge 配置（config/imeinput.cfg）读取 IME 后端模式。
     * {@code general.backend = AUTO | TSF | IMM32}，默认 AUTO（先试 TSF，
     * 失败静默降级 IMM32）；非法取值回退 AUTO 并记日志。
     * Reads the IME backend mode from the Forge config (config/imeinput.cfg):
     * {@code general.backend = AUTO | TSF | IMM32}, defaulting to AUTO (try TSF,
     * silently fall back to IMM32); invalid values fall back to AUTO with a log.
     *
     * @param config 已加载的 Forge 配置（调用方负责 load/save）
     * @return {@link IMEBridge#BACKEND_AUTO} / {@code BACKEND_TSF} / {@code BACKEND_IMM32}
     */
    private int loadBackendConfig(Configuration config) {
        String value = config.getString("backend", Configuration.CATEGORY_GENERAL, "AUTO",
                "IME backend: AUTO = try TSF then fall back to IMM32; "
                        + "TSF / IMM32 = force that backend. "
                        + "IME 后端：AUTO = 先试 TSF 失败降级 IMM32；TSF / IMM32 = 强制指定后端。",
                new String[] { "AUTO", "TSF", "IMM32" });
        if ("TSF".equalsIgnoreCase(value)) {
            return IMEBridge.BACKEND_TSF;
        }
        if ("IMM32".equalsIgnoreCase(value)) {
            return IMEBridge.BACKEND_IMM32;
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