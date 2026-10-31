package cn.ksmcbrigade.mb.datagen;

import cn.ksmcbrigade.mb.MsgBoardMod;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;

/** Both crafting recipes, generated instead of hand written. */
public final class MsgBoardRecipeProvider extends FabricRecipeProvider {

    /** Any wooden sign accepts the middle slot, matching the vanilla {@code #minecraft:signs} tag. */
    private static final TagKey<Item> SIGNS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("minecraft", "signs"));

    public MsgBoardRecipeProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        return new RecipeProvider(registries, output) {
            @Override
            public void buildRecipes() {
                HolderLookup.RegistryLookup<Item> itemLookup = registries.lookupOrThrow(Registries.ITEM);

                // 铁 红石 铁 / 红石 告示牌 红石 / 铁 红石 铁
                ShapedRecipeBuilder.shaped(itemLookup, RecipeCategory.MISC, MsgBoardMod.MSG_BOARD)
                        .define('I', Items.IRON_INGOT)
                        .define('R', Items.REDSTONE)
                        .define('S', SIGNS)
                        .pattern("IRI")
                        .pattern("RSR")
                        .pattern("IRI")
                        .unlockedBy("has_redstone", has(Items.REDSTONE))
                        .save(output);

                // 铜 / 铜 海洋之心 铜 / 铜
                ShapedRecipeBuilder.shaped(itemLookup, RecipeCategory.MISC, MsgBoardMod.TIME_CAPSULE)
                        .define('C', Items.COPPER_INGOT)
                        .define('H', Items.HEART_OF_THE_SEA)
                        .pattern(" C ")
                        .pattern("CHC")
                        .pattern(" C ")
                        .unlockedBy("has_heart_of_the_sea", has(Items.HEART_OF_THE_SEA))
                        .save(output);
            }
        };
    }

    @Override
    public String getName() {
        return "Message board recipes";
    }
}
