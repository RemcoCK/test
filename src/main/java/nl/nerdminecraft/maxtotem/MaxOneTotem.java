package nl.nerdminecraft.maxtotem;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaxOneTotem implements ModInitializer {
    public static final String MOD_ID = "nerd_max_one_totem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Nerd Max One Totem geladen: spelers kunnen maximaal 1 Totem of Undying oppakken.");
    }
}
