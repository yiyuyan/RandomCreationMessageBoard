package cn.ksmcbrigade.mb.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class MsgBoardModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Development aid: only active when the marker file is present.
        Path smokeFlag = FabricLoader.getInstance().getConfigDir().resolve("crc_msg_board_smoke.flag");
        if (Files.exists(smokeFlag)) {
            SmokeTest.register();
        }
    }
}
