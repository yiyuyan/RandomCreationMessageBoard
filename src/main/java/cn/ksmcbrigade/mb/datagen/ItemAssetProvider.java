package cn.ksmcbrigade.mb.datagen;

import cn.ksmcbrigade.mb.MsgBoardMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generates the client item assets for both items.
 *
 * <p>Since 1.21.4 an item needs two files: a model under {@code models/item} and an
 * <em>item definition</em> under {@code items}. A missing item definition is exactly what makes
 * an item render as the black/purple missing-texture cube. {@code ItemModelGenerators} in
 * 26.1.2 no longer exposes per-item generators to mods, so both files are written here.</p>
 */
public final class ItemAssetProvider implements DataProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record ItemAsset(String name) {
    }

    private static final List<ItemAsset> ITEMS = List.of(
            new ItemAsset("msg_board"),
            new ItemAsset("copper_time_capsules"));

    private final FabricPackOutput output;

    public ItemAssetProvider(FabricPackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        // getOutputFolder(RESOURCE_PACK) already points at the "assets" root.
        Path assets = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(MsgBoardMod.MOD_ID);
        Path modelsDir = assets.resolve("models").resolve("item");
        Path definitionsDir = assets.resolve("items");

        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (ItemAsset item : ITEMS) {
            futures.add(DataProvider.saveStable(cache, modelJson(item.name()), modelsDir.resolve(item.name() + ".json")));
            futures.add(DataProvider.saveStable(cache, definitionJson(item.name()),
                    definitionsDir.resolve(item.name() + ".json")));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** {@code models/item/<name>.json} - the flat sprite model. */
    private JsonObject modelJson(String name) {
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", MsgBoardMod.MOD_ID + ":item/" + name);

        JsonObject json = new JsonObject();
        json.addProperty("parent", "minecraft:item/generated");
        json.add("textures", textures);
        return json;
    }

    /** {@code items/<name>.json} - the item definition that points at the model. */
    private JsonObject definitionJson(String name) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", MsgBoardMod.MOD_ID + ":item/" + name);

        JsonObject json = new JsonObject();
        json.add("model", model);
        return json;
    }

    @Override
    public String getName() {
        return "Item assets (models + item definitions)";
    }
}
