package dev.frontiersurvival.client;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.FrontierArrow;
import dev.frontiersurvival.entity.GuardEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GrassColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FrontierSurvival.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FrontierClient {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FrontierSurvival.GUARD.get(), context -> new PersonRenderer<>(context, "guard"));
        event.registerEntityRenderer(FrontierSurvival.BANDIT.get(), context -> new PersonRenderer<>(context, "bandit"));
        event.registerEntityRenderer(FrontierSurvival.BANDIT_LEADER.get(), context -> new PersonRenderer<>(context, "bandit_leader"));
        event.registerEntityRenderer(FrontierSurvival.QUARTERMASTER.get(), context -> new PersonRenderer<>(context, "quartermaster"));
        event.registerEntityRenderer(FrontierSurvival.ARROW.get(), FrontierArrowRenderer::new);
    }

    @SubscribeEvent
    public static void blockColours(RegisterColorHandlersEvent.Block event) {
        // Settlement turf takes the local biome's grass tint, exactly like the grass it replaces.
        event.register((state, level, pos, tint) -> level != null && pos != null
                ? BiomeColors.getAverageGrassColor(level, pos) : GrassColor.getDefaultColor(),
                FrontierSurvival.SETTLEMENT_GRASS.get());
    }

    @SubscribeEvent
    public static void itemColours(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> GrassColor.getDefaultColor(), FrontierSurvival.SETTLEMENT_GRASS_ITEM.get());
    }

    private static final class PersonRenderer<T extends Mob> extends HumanoidMobRenderer<T, HumanoidModel<T>> {
        private final ResourceLocation skin;

        private PersonRenderer(EntityRendererProvider.Context context, String name) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.45F);
            skin = ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, "textures/entity/" + name + ".png");
            addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
        }

        @Override
        public void render(T entity, float yaw, float partial, com.mojang.blaze3d.vertex.PoseStack pose,
                net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
            model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            HumanoidModel.ArmPose weapon = entity.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY
                    : entity.getMainHandItem().is(Items.BOW) && entity.isAggressive()
                    ? HumanoidModel.ArmPose.BOW_AND_ARROW : HumanoidModel.ArmPose.ITEM;
            if (entity.getMainArm() == HumanoidArm.RIGHT) {
                model.rightArmPose = weapon;
                model.leftArmPose = entity.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
            } else {
                model.leftArmPose = weapon;
                model.rightArmPose = entity.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
            }
            super.render(entity, yaw, partial, pose, buffers, light);
        }

        @Override
        public ResourceLocation getTextureLocation(T entity) { return skin; }
    }

    private static final class FrontierArrowRenderer extends ArrowRenderer<FrontierArrow> {
        private FrontierArrowRenderer(EntityRendererProvider.Context context) { super(context); }
        @Override
        public ResourceLocation getTextureLocation(FrontierArrow entity) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/projectiles/arrow.png");
        }
    }

    private FrontierClient() {}
}
