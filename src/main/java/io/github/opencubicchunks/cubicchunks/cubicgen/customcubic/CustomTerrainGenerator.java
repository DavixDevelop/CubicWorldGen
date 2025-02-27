/*
 *  This file is part of Cubic World Generation, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2020 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.cubicgen.customcubic;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubeGeneratorsRegistry;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.CubePopulatorEvent;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.ICubicPopulator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.event.PopulateCubeEvent;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.ICubicStructureGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.event.InitCubicStructureGeneratorEvent;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.feature.CubicFeatureGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.structure.feature.ICubicFeatureGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.BasicCubeGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.CustomCubicMod;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.CubicBiome;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.biome.IBiomeBlockReplacer;
import io.github.opencubicchunks.cubicchunks.cubicgen.common.world.storage.IWorldInfoAccess;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.builder.BiomeSource;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.builder.IBuilder;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.builder.NoiseSource;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.CubicCaveGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.CubicRavineGenerator;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.structure.feature.CubicStrongholdGenerator;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.InitMapGenEvent;
import net.minecraftforge.event.terraingen.InitMapGenEvent.EventType;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.lwjgl.input.Keyboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

import static io.github.opencubicchunks.cubicchunks.api.util.Coords.blockToLocal;

/**
 * A terrain generator that supports infinite(*) worlds
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class CustomTerrainGenerator extends BasicCubeGenerator {

    private static final int CACHE_SIZE_2D = 16 * 16;
    private static final int CACHE_SIZE_3D = 16 * 16 * 16;
    private static final ToIntFunction<Vec3i> HASH_2D = (v) -> v.getX() + v.getZ() * 5;
    private static final ToIntFunction<Vec3i> HASH_3D = (v) -> v.getX() + v.getZ() * 5 + v.getY() * 25;
    private final Map<CustomGeneratorSettings.IntAABB, CustomTerrainGenerator> areaGenerators = new HashMap<>();
    // Number of octaves for the noise function
    private IBuilder terrainBuilder;
    private BiomeSource biomeSource;
    private CustomGeneratorSettings conf;
    private final Map<Biome, ICubicPopulator> populators = new HashMap<>();

    private boolean fillCubeBiomes;

    //TODO: Implement more structures
    @Nonnull private ICubicStructureGenerator caveGenerator;
    @Nonnull private ICubicStructureGenerator ravineGenerator;
    @Nonnull private ICubicFeatureGenerator strongholds;

    public CustomTerrainGenerator(World world, final long seed) {
        this(world, world.getBiomeProvider(), CustomGeneratorSettings.getFromWorld(world), seed);
    }

    public CustomTerrainGenerator(World world, BiomeProvider biomeProvider, CustomGeneratorSettings settings, final long seed) {
        this(world, biomeProvider, settings, seed, true);
    }

    private CustomTerrainGenerator(World world, BiomeProvider biomeProvider, CustomGeneratorSettings settings, final long seed, boolean isMainLayer) {
        super(world);
        init(world, biomeProvider, settings, seed, isMainLayer);
    }

    public void reloadPreset(String settings) {
        ((IWorldInfoAccess) world.getWorldInfo()).setGeneratorOptions(settings);
        world.provider.setWorld(world);// this re-creates biome provider
        init(world, world.getBiomeProvider(), CustomGeneratorSettings.getFromWorld(world), world.getSeed(), true);
    }

    private void init(World world, BiomeProvider biomeProvider, CustomGeneratorSettings settings, long seed, boolean isMainLayer) {
        this.conf = settings;

        this.populators.clear();

        for (Biome biome : ForgeRegistries.BIOMES) {
            CubicBiome cubicBiome = CubicBiome.getCubic(biome);
            populators.put(biome, cubicBiome.getDecorator(conf));
        }

        InitCubicStructureGeneratorEvent caveEvent = new InitCubicStructureGeneratorEvent(EventType.CAVE, new CubicCaveGenerator(), world);
        InitCubicStructureGeneratorEvent strongholdsEvent = new InitCubicStructureGeneratorEvent(
                EventType.STRONGHOLD, new CubicStrongholdGenerator(conf), world);
        InitCubicStructureGeneratorEvent ravineEvent = new InitCubicStructureGeneratorEvent(EventType.RAVINE, new CubicRavineGenerator(conf), world);

        MinecraftForge.TERRAIN_GEN_BUS.post(caveEvent);
        MinecraftForge.TERRAIN_GEN_BUS.post(strongholdsEvent);
        MinecraftForge.TERRAIN_GEN_BUS.post(ravineEvent);

        this.caveGenerator = caveEvent.getNewGen();
        this.strongholds = (CubicFeatureGenerator) strongholdsEvent.getNewGen();
        this.ravineGenerator = ravineEvent.getNewGen();

        this.fillCubeBiomes = !isMainLayer;
        this.biomeSource = new BiomeSource(world, conf.createBiomeBlockReplacerConfig(), biomeProvider, 2);
        initGenerator(seed);

        this.areaGenerators.clear();

        if (settings.cubeAreas != null) {
            for (Map.Entry<CustomGeneratorSettings.IntAABB, CustomGeneratorSettings> entry : settings.cubeAreas.map) {
                this.areaGenerators.put(entry.getKey(), new CustomTerrainGenerator(world, CustomCubicWorldType.makeBiomeProvider(world,
                        entry.getValue()), entry.getValue(), seed, false));
            }
        }
    }

    private void initGenerator(long seed) {
        Random rnd = new Random(seed);

        IBuilder selector = NoiseSource.perlin()
                .seed(rnd.nextLong())
                .normalizeTo(-1, 1)
                .frequency(conf.selectorNoiseFrequencyX, conf.selectorNoiseFrequencyY, conf.selectorNoiseFrequencyZ)
                .octaves(conf.selectorNoiseOctaves)
                .create()
                .mul(conf.selectorNoiseFactor).add(conf.selectorNoiseOffset).clamp(0, 1);

        IBuilder low = NoiseSource.perlin()
                .seed(rnd.nextLong())
                .normalizeTo(-1, 1)
                .frequency(conf.lowNoiseFrequencyX, conf.lowNoiseFrequencyY, conf.lowNoiseFrequencyZ)
                .octaves(conf.lowNoiseOctaves)
                .create()
                .mul(conf.lowNoiseFactor).add(conf.lowNoiseOffset);

        IBuilder high = NoiseSource.perlin()
                .seed(rnd.nextLong())
                .normalizeTo(-1, 1)
                .frequency(conf.highNoiseFrequencyX, conf.highNoiseFrequencyY, conf.highNoiseFrequencyZ)
                .octaves(conf.highNoiseOctaves)
                .create()
                .mul(conf.highNoiseFactor).add(conf.highNoiseOffset);

        IBuilder randomHeight2d = NoiseSource.perlin()
                .seed(rnd.nextLong())
                .normalizeTo(-1, 1)
                .frequency(conf.depthNoiseFrequencyX, 0, conf.depthNoiseFrequencyZ)
                .octaves(conf.depthNoiseOctaves)
                .create()
                .mul(conf.depthNoiseFactor).add(conf.depthNoiseOffset)
                .mulIf(IBuilder.NEGATIVE, -0.3).mul(3).sub(2).clamp(-2, 1)
                .divIf(IBuilder.NEGATIVE, 2 * 2 * 1.4).divIf(IBuilder.POSITIVE, 8)
                .mul(0.2 * 17 / 64.0)
                .cached2d(CACHE_SIZE_2D, HASH_2D);

        IBuilder height = ((IBuilder) biomeSource::getHeight)
                .mul(conf.heightFactor)
                .add(conf.heightOffset);

        double specialVariationFactor = conf.specialHeightVariationFactorBelowAverageY;
        IBuilder volatility = ((IBuilder) biomeSource::getVolatility)
                .mul((x, y, z) -> height.get(x, y, z) > y ? specialVariationFactor : 1)
                .mul(conf.heightVariationFactor)
                .add(conf.heightVariationOffset);

        this.terrainBuilder = selector
                .lerp(low, high).add(randomHeight2d).mul(volatility).add(height)
                .sub(volatility.signum().mul((x, y, z) -> y))
                .cached(CACHE_SIZE_3D, HASH_3D);
    }

    @Override
    public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ) { // legacy method
        return this.generateCube(cubeX, cubeY, cubeZ, new CubePrimer());
    }

    @Override
    public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ, CubePrimer primer) {
        if (!areaGenerators.isEmpty()) {
            for (CustomGeneratorSettings.IntAABB aabb : areaGenerators.keySet()) {
                if (!aabb.contains(cubeX, cubeY, cubeZ)) {
                    continue;
                }
                return areaGenerators.get(aabb).generateCube(cubeX, cubeY, cubeZ, primer);
            }
        }
        generate(primer, cubeX, cubeY, cubeZ);
        generateStructures(primer, new CubePos(cubeX, cubeY, cubeZ));
        if (fillCubeBiomes) {
            fill3dBiomes(cubeX, cubeY, cubeZ, primer);
        }
        return primer;
    }

    private void fill3dBiomes(int cubeX, int cubeY, int cubeZ, CubePrimer primer) {
        int minX = cubeX * 4;
        int minY = cubeY * 4;
        int minZ = cubeZ * 4;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                for (int dz = 0; dz < 4; dz++) {
                    primer.setBiome(dx, dy, dz,
                            biomeSource.getBiome(minX + dx * 4, minY + dy * 4, minZ + dz * 4).getBiome());
                }
            }
        }
    }

    @Override public void populate(ICube cube) {
        if (!areaGenerators.isEmpty()) {
            for (CustomGeneratorSettings.IntAABB aabb : areaGenerators.keySet()) {
                if (!aabb.contains(cube.getX(), cube.getY(), cube.getZ())) {
                    continue;
                }
                areaGenerators.get(aabb).populate(cube);
                return;
            }
        }

        /**
         * If event is not canceled we will use default biome decorators and
         * cube populators from registry.
         **/
        if (!MinecraftForge.EVENT_BUS.post(new CubePopulatorEvent(world, cube))) {
            CubicBiome cubicBiome = CubicBiome.getCubic(cube.getWorld().getBiome(Coords.getCubeCenter(cube)));

            CubePos pos = cube.getCoords();
            // For surface generators we should actually use special RNG with
            // seed
            // that depends only in world seed and cube X/Z
            // but using this for surface generation doesn't cause any
            // noticeable issues
            Random rand = Coords.coordsSeedRandom(cube.getWorld().getSeed(), cube.getX(), cube.getY(), cube.getZ());

            MinecraftForge.EVENT_BUS.post(new PopulateCubeEvent.Pre(world, rand, pos.getX(), pos.getY(), pos.getZ(), false));
            strongholds.generateStructure(world, rand, pos);
            populators.get(cubicBiome.getBiome()).generate(world, rand, pos, cubicBiome.getBiome());
            MinecraftForge.EVENT_BUS.post(new PopulateCubeEvent.Post(world, rand, pos.getX(), pos.getY(), pos.getZ(), false));
            CubeGeneratorsRegistry.generateWorld(world, rand, pos, cubicBiome.getBiome()); }
    }

    @Override
    public void recreateStructures(ICube cube) {
        this.strongholds.generate(world, null, cube.getCoords());
    }

    @Nullable @Override
    public BlockPos getClosestStructure(String name, BlockPos pos, boolean findUnexplored) {
        if ("Stronghold".equals(name)) {
            return strongholds.getNearestStructurePos((World) world, pos, true);
        }
        return null;
    }

    /**
     * Generate the cube as the specified location
     *
     * @param cubePrimer cube primer to use
     * @param cubeX cube x location
     * @param cubeY cube y location
     * @param cubeZ cube z location
     */
    private void generate(final CubePrimer cubePrimer, int cubeX, int cubeY, int cubeZ) {
        // when debugging is enabled, allow reloading generator settings after pressing L
        // no need to restart after applying changes.
        // Seed it changed to some constant because world isn't easily accessible here
        if (CustomCubicMod.DEBUG_ENABLED && FMLCommonHandler.instance().getSide().isClient() && Keyboard.isKeyDown(Keyboard.KEY_L)) {
            initGenerator(42);
        }

        BlockPos start = new BlockPos(cubeX * 4, cubeY * 2, cubeZ * 4);
        BlockPos end = start.add(4, 2, 4);
        terrainBuilder.forEachScaled(start, end, new Vec3i(4, 8, 4),
                (x, y, z, dx, dy, dz, v) ->
                        cubePrimer.setBlockState(
                                blockToLocal(x), blockToLocal(y), blockToLocal(z),
                                getBlock(x, y, z, dx, dy, dz, v))
        );

    }

    /**
     * Retrieve the blockstate appropriate for the specified builder entry
     *
     * @return The block state
     */
    private IBlockState getBlock(int x, int y, int z, double dx, double dy, double dz, double density) {
        List<IBiomeBlockReplacer> replacers = biomeSource.getReplacers(x, y, z);
        IBlockState block = Blocks.AIR.getDefaultState();
        int size = replacers.size();
        for (int i = 0; i < size; i++) {
            block = replacers.get(i).getReplacedBlock(block, x, y, z, dx, dy, dz, density);
        }
        return block;
    }

    public void generateStructures(CubePrimer cube, CubePos cubePos) {
        // generate world populator
        if (this.conf.caves) {
            this.caveGenerator.generate(world, cube, cubePos);
        }
        if (this.conf.ravines) {
            this.ravineGenerator.generate(world, cube, cubePos);
        }
        if (this.conf.strongholds) {
            this.strongholds.generate(world, cube, cubePos);
        }
    }

    public final ICubicStructureGenerator getCaveGenerator() {
        return caveGenerator;
    }

    public final ICubicFeatureGenerator getStrongholds() {
        return strongholds;
    }

    public final ICubicStructureGenerator getRavineGenerator() {
        return ravineGenerator;
    }

    public CustomGeneratorSettings getConfig() {
        return conf;
    }

    public Map<Biome, ICubicPopulator> getPopulators() {
        return populators;
    }
}
