package io.github.octomiku01.lodgen.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.octomiku01.lodgen.LodConfigScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Adds the settings screen to Mod Menu, when Cloth Config is there to build it */
public final class LodModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return parent -> null;
        }
        return LodConfigScreen::create;
    }
}
