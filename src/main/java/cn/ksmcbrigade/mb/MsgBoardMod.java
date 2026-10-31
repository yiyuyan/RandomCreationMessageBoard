package cn.ksmcbrigade.mb;

import cn.ksmcbrigade.mb.item.MsgBoardItem;
import cn.ksmcbrigade.mb.item.TimeCapsuleItem;
import cn.ksmcbrigade.mb.network.ModNetwork;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import java.util.Optional;

public class MsgBoardMod implements ModInitializer {

    public static final String MOD_ID = "crc_msg_board";
    public static final ResourceKey<PaintingVariant> GO_REST =
            ResourceKey.create(Registries.PAINTING_VARIANT, rl("go_rest"));

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceKey<Item> MSG_BOARD_KEY = ResourceKey.create(Registries.ITEM, rl("msg_board"));
    public static final ResourceKey<Item> TIME_CAPSULE_KEY =
            ResourceKey.create(Registries.ITEM, rl("copper_time_capsules"));

    public static final Item MSG_BOARD = new MsgBoardItem(new Item.Properties().setId(MSG_BOARD_KEY).stacksTo(1));
    public static final Item TIME_CAPSULE =
            new TimeCapsuleItem(new Item.Properties().setId(TIME_CAPSULE_KEY).stacksTo(1));

    /**
     * "Tools &amp; Utilities" tab. {@code CreativeModeTabs.TOOLS_AND_UTILITIES} is private in
     * this version, so the equivalent key is rebuilt from its registry id.
     */
    public static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath("minecraft", "tools_and_utilities"));

    @Override
    public void onInitialize() {

        Registry.register(BuiltInRegistries.ITEM, MSG_BOARD_KEY, MSG_BOARD);
        Registry.register(BuiltInRegistries.ITEM, TIME_CAPSULE_KEY, TIME_CAPSULE);

        ModNetwork.init();

        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES).register(output -> {
            output.accept(MSG_BOARD);
            output.accept(TIME_CAPSULE);
        });

        DynamicRegistrySetupCallback.EVENT.register(registryView -> registryView.getOptional(Registries.PAINTING_VARIANT).ifPresent((r) -> Registry.register(
                r,
                GO_REST,
                new PaintingVariant(4, 3, GO_REST.identifier(), Optional.of(Component.literal("GoRest")), Optional.empty())
        )));

        LOGGER.info("Hello to {}", MsgBoardMod.class.getSimpleName());
    }

    public static Identifier rl(String path) {
        return Identifier.tryBuild(MOD_ID, path);
    }
}
