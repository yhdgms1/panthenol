package dev.yhdgms1.panthenol.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {
    /** Game config directory ({@code .minecraft/config}). */
    Path getConfigDirectory();
}
