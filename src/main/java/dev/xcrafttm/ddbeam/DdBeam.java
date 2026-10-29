package dev.xcrafttm.ddbeam;

import dev.xcrafttm.ddbeam.net.BeamNet;
import dev.xcrafttm.ddbeam.server.CastRelay;
import dev.xcrafttm.ddbeam.server.ServerConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dream Displays Beam, both sides: the packets, and the server relay that passes casts on to other players. */
public final class DdBeam implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ddbeam");

    @Override
    public void onInitialize() {
        ServerConfig.load();
        BeamNet.registerTypes();
        CastRelay.register();
    }
}
