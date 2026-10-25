package io.github.octomiku01.lodgen.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.octomiku01.lodgen.LodConfigScreen;

/** Adds the settings screen to Mod Menu */
public final class LodModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LodConfigScreen::new;
    }
}
