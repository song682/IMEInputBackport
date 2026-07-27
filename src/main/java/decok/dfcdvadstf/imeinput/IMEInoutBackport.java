package decok.dfcdvadstf.imeinput;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MODID, name = Tags.NAME, version = Tags.VERSION, useMetadata = true)
public class IMEInoutBackport {
    public static Logger logger = LogManager.getLogger(Tags.NAME);

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Client-only: install IME window subclassing & callbacks.
        // 仅客户端：安装 IME 窗口子类化与回调（避免在专用服务端加载客户端类）。
        if (event.getSide().isClient()) {
            IMEHandler.init();
        }
    }

}