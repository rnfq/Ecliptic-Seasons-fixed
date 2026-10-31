package com.teamtea.eclipticseasons.client.render;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.teamtea.eclipticseasons.EclipticSeasons;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.registry.EffectRegistry;
import com.teamtea.eclipticseasons.client.ClientEventHandler;
import com.teamtea.eclipticseasons.config.CommonConfig;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.io.IOException;
import java.util.ArrayList;

public class WorldRenderer {
    private static final ResourceLocation HEAT_STROKE_BLUR = EclipticSeasons.rl("shaders/post/box_blur.json");
    // Same timing and strength as before: 0 -> 1 over 10 ticks, RadiusMultiplier peaks at 0.3.
    private static final float BLUR_FADE_STEP = 0.1f;
    private static final float BLUR_MAX_RADIUS_MULTIPLIER = 0.3f;

    // Our own post chain, built once and kept for the whole session. It is never put into
    // GameRenderer#postEffect: we draw it ourselves (renderHeatStrokeBlur), so other post effects
    // (spectator shaders, F5, other mods) can't block, close or replace it.
    // Upstream reloaded GameRenderer's effect on every tick of the fade-in (~11 times, each linking
    // 6 shader programs and allocating a framebuffer), which caused the stutter.
    private static PostChain blurChain = null;
    private static boolean blurLoadFailed = false;
    private static int blurWidth = -1;
    private static int blurHeight = -1;
    private static boolean hadEffect = false;
    private static float blurLevel = 0f;
    private static float appliedBlurLevel = -1f;

    public static void applyEffect(GameRenderer gameRenderer, LocalPlayer player) {
        if (player == null || !CommonConfig.Temperature.heatStroke.get()) {
            hadEffect = false;
            blurLevel = 0f;
            return;
        }

        // Build it on the first tick in a world instead of when the effect is gained.
        if (blurChain == null && !blurLoadFailed) loadBlurChain();

        // Freeze the fade while paused.
        if (Minecraft.getInstance().isPaused()) return;

        boolean hasEffect = false;
        for (var activeEffect : player.getActiveEffectsMap().keySet()) {
            if (activeEffect.is(EffectRegistry.Effects.HEAT_STROKE)) {
                hasEffect = true;
                break;
            }
        }
        if (hasEffect && !hadEffect) {
            PostChain current = gameRenderer.currentEffect();
            EclipticSeasons.LOGGER.debug("Heatstroke blur on (chain ready: {}, vanilla post effect: {})",
                    blurChain != null, current == null ? "none" : current.getName());
        }
        hadEffect = hasEffect;

        blurLevel = hasEffect
                ? Math.min(blurLevel + BLUR_FADE_STEP, 1f)
                : Math.max(blurLevel - BLUR_FADE_STEP, 0f);

        if (blurChain != null && blurLevel != appliedBlurLevel) {
            appliedBlurLevel = blurLevel;
            blurChain.setUniform("RadiusMultiplier", blurLevel * BLUR_MAX_RADIUS_MULTIPLIER);
        }
    }

    private static void loadBlurChain() {
        Minecraft mc = Minecraft.getInstance();
        try {
            blurChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), HEAT_STROKE_BLUR);
            blurWidth = -1;
            appliedBlurLevel = -1f;
        } catch (IOException | JsonSyntaxException e) {
            blurLoadFailed = true;
            EclipticSeasons.LOGGER.warn("Failed to load heatstroke blur {}", HEAT_STROKE_BLUR, e);
        }
    }

    // Called from RenderGuiEvent.Pre, i.e. after the world (and any vanilla post effect) and before the HUD.
    // State handling mirrors vanilla's menu blur in Screen#renderBlurredBackground.
    public static void renderHeatStrokeBlur(float partialTick) {
        if (blurChain == null || blurLevel <= 0f) return;

        Window window = Minecraft.getInstance().getWindow();
        if (window.getWidth() != blurWidth || window.getHeight() != blurHeight) {
            blurWidth = window.getWidth();
            blurHeight = window.getHeight();
            blurChain.resize(blurWidth, blurHeight);
        }

        RenderSystem.disableDepthTest();
        blurChain.process(partialTick);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        // Gui#render enables depth test right before RenderGuiEvent.Pre.
        RenderSystem.enableDepthTest();
    }


    public static boolean isSectionLoad(SectionPos sectionPos) {
        return isSectionLoad(sectionPos, 1);
    }

    public static boolean isSectionLoad(SectionPos pPos, int range) {
        boolean load = true;
        if (Minecraft.getInstance().level instanceof Level level) {
            for (int i = -range + 1; i < range; i++) {
                for (int j = -range + 1; j < range; j++) {
                    load &= level.getChunk(pPos.getX() + i, pPos.getZ() + j,
                            ChunkStatus.FULL, false) != null;
                    if (!load) break;
                }
            }
        }
        // if(!load)
        //     EclipticSeasons.logger(pPos);
        return load;
    }

    public static void setSectionDirty(SectionPos sectionPos) {
        if (isSectionLoad(sectionPos)) {
            Minecraft.getInstance().levelRenderer.setSectionDirty(sectionPos.x(), sectionPos.y(), sectionPos.z());
        }
    }

    public static void setSectionDirtyWithNeighbors(SectionPos sectionPos) {
        if (isSectionLoad(sectionPos, 2)) {
            Minecraft.getInstance().levelRenderer.setSectionDirtyWithNeighbors(sectionPos.x(), sectionPos.y(), sectionPos.z());
        }
    }

    public static void setSectionDirtyRandomly(SectionPos sectionPos) {
        if (Minecraft.getInstance().level != null) {
            RandomSource random = Minecraft.getInstance().level.random;
            int lastViewDistance = (int) (Minecraft.getInstance().levelRenderer.getLastViewDistance() - 1);
            for (int i = 0; i < random.nextInt(8) + 4; i++) {
                {
                    setSectionDirtyWithNeighbors(SectionPos.of(sectionPos.x() + 2 * (random.nextInt(lastViewDistance)) - lastViewDistance,
                            sectionPos.y(),
                            sectionPos.z() + 2 * (random.nextInt(lastViewDistance)) - lastViewDistance));
                }
            }
        }
    }


    public static void setAllDirty(SectionPos centerPos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        level.clearTintCaches();
        int pSectionX = centerPos.x();
        //int pSectionY = centerPos.y();
        int pSectionZ = centerPos.z();
        int d = (int) Minecraft.getInstance().levelRenderer.getLastViewDistance();
        for (int j = pSectionZ - d; j <= pSectionZ + d; j++) {
            for (int i = pSectionX - d; i <= pSectionX + d; i++) {
                var chunk = MapChecker.getChunkView(level, i, j);
                if (chunk != null) {
                    IntArraySet set = new IntArraySet();
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            set.add(SectionPos.posToSectionCoord(chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)));
                        }
                    }
                    for (IntIterator it = set.iterator(); it.hasNext(); ) {
                        int pSectionY = it.nextInt();
                        setSectionDirty(SectionPos.of(i, pSectionY, j));
                    }
                    //for (int k = pSectionY - 3; k <= pSectionY + 1; k++) {
                    //    setSectionDirty(SectionPos.of(i, k, j));
                    //}
                }
            }
        }
    }
}
