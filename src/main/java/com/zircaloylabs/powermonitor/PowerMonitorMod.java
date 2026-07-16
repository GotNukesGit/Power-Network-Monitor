package com.zircaloylabs.powermonitor;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
/**
 * Mod entry point. This class was NOT present before this session -- without
 * an @Mod-annotated class, Forge has nothing to load, regardless of how
 * correct the cover/item/registration logic underneath it is. Registration
 * runs in preInit, matching the comment on ModPowerMonitorRegistration.
 */
@Mod(modid = PowerMonitorMod.MODID, version = PowerMonitorMod.VERSION,
        name = "Power Network Monitor", acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "required-after:gregtech")
public class PowerMonitorMod {
    public static final String MODID = "powermonitor";
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LogManager.getLogger(MODID);
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("Power Network Monitor preInit -- registering item, covers, recipes");
        ModPowerMonitorRegistration.registerAll();
        if (event.getSide() == Side.CLIENT) {
            PowerMonitorIcons.registerSelf();
        }
    }
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Waila registration goes through Waila's IMC handshake, which is
        // processed between init and postInit -- so the message must be sent
        // by init at the latest (same phase GT itself sends its own).
        PowerMonitorWailaProvider.init();
    }
}
