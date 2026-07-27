package dev.yhdgms1.panthenol.platform;

import dev.yhdgms1.panthenol.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Uses Fabric Loader API exposed by Quilt's interop layer.
 */
public class QuiltPlatformHelper implements IPlatformHelper {
    @Override
    public Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
