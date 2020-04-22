package io.github.terra121;

import com.google.common.collect.Sets;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.terra121.dataset.OpenStreetMaps;
import io.github.terra121.dataset.Region;
import io.github.terra121.projection.GeographicProjection;
import io.github.terra121.utils.SetBlockingQueue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.*;

import static io.github.terra121.dataset.OpenStreetMaps.Coord;
import static net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import static net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;

/**
 * A class that loads 3x3 region chunks depending on the current player position.
 * Also, this classes pre-generates Edges in order to be used later by the EarthTerrainProcessor.
 *
 * @author z3nth10n
 */
@Mod.EventBusSubscriber
public class PlayerRegionDispatcher {
    private static final Map<UUID, CubePos> latestPos = new HashMap<>();

    private static DispatcherRunnable runnable;

    public static void init(OpenStreetMaps maps) {
        System.out.println("Created runnable");

        runnable = new DispatcherRunnable(maps);
        Thread dispatcherThread = new Thread(runnable, "t121_dispatcher");
        dispatcherThread.start();
    }

    public static class DispatcherRunnable implements Runnable {
        private final SetBlockingQueue<Optional<RegionModel>> unprocessedRegions = new SetBlockingQueue<>();
        private final Set<Coord> generatedCubes = Sets.newConcurrentHashSet();

        private OpenStreetMaps maps;

        private DispatcherRunnable() {}

        public DispatcherRunnable(OpenStreetMaps maps) {
            this.maps = maps;
        }

        /**
         * Runs the runnable.
         */
        @Override
        public void run() {
            try {
                //noinspection StatementWithEmptyBody
                while (tick()) {
                }
            } catch (InterruptedException e) {
                TerraMod.LOGGER.error("Error occurred on Dispatcher runnable", e);
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Processes the queued regions.
         * @return
         * @throws InterruptedException
         */
        public boolean tick()
                throws InterruptedException {
            Optional<RegionModel> curRegion = unprocessedRegions.take();
            if (!curRegion.isPresent()) {
                return false;
            }

            RegionModel model = curRegion.get();
            Region region = model.region;
            maps.regionDownload(region);

            unprocessedRegions.remove(curRegion);
            generatedCubes.add(new Coord(model.cubeX, model.cubeZ));

            return true;
        }

        /**
         * Add region to the current runnable.
         * @param cubeX
         * @param cubeZ
         */
        public void addRegion(int cubeX, int cubeZ) {
            unprocessedRegions.add(Optional.of(getRegion(cubeX, cubeZ)));
        }

        /**
         * Check if
         * @param cubeX
         * @param cubeZ
         * @return
         */
        public boolean isBusy(int cubeX, int cubeZ) {
            return unprocessedRegions.contains(Optional.of(getRegion(cubeX, cubeZ)));
        }

        private RegionModel getRegion(int cubeX, int cubeZ) {
            double[] c = projection.toGeo(cubeX * ICube.SIZE_D, cubeZ * ICube.SIZE_D);
            Coord coord = OpenStreetMaps.getRegion(c[0], c[1]);
            return new RegionModel(cubeX, cubeZ, new Region(coord, maps.water));
        }

        public boolean isGenerated(int cubeX, int cubeZ) {
            return generatedCubes.contains(new Coord(cubeX, cubeZ));
        }

        /**
         * Stops the current runnable.
         */
        public void stop() {
            try {
                unprocessedRegions.put(Optional.empty());
            } catch (InterruptedException e) {
                TerraMod.LOGGER.error(e);
            }
        }

        public static class RegionModel {
            public int cubeX;
            public int cubeZ;
            public Region region;

            private RegionModel() {}

            public RegionModel(int cubeX, int cubeZ, Region region) {
                this.cubeX = cubeX;
                this.cubeZ = cubeZ;
                this.region = region;
            }
        }
    }

    /**
     * Get called on every entity update.
     * Checks if the player moved on a new chunk to pre-generate the current region (32x32).
     * @param e
     */
    @SubscribeEvent
    public static void onEntityUpdate(LivingUpdateEvent e) {
        // This method is cool, because the player won't move until the region is entire loaded todo: test
        if (e.getEntity() instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) e.getEntity();
            UUID uuid = player.getUniqueID();
            CubePos lPos = latestPos.getOrDefault(uuid, null);

            double pX = player.posX;
            double pY = player.posY;
            double pZ = player.posZ;

            /* // TODO: Work on Edge
            CubePos pos = CubePos.fromBlockCoords((int) pX, (int) pY, (int) pZ);
            pos = new CubePos(pos.getX(), 0, pos.getZ());
            // boolean forceGenerating = latestPos == null;

            if (pos != lPos && lPos != null) {
                if(!runnable.isGenerated(pos.getX(), pos.getZ()) && !runnable.isBusy(pos.getX(), pos.getZ())) {
                    System.out.println("["+pos+"] generating from moving! ["+uuid+"]");
                    runnable.addRegion(pos.getX(), pos.getZ());
                }
            }
            */

            latestPos.put(uuid, pos);
        }
    }

    /**
     * Trigger player logout in order to remove the latestPos key.
     * @param e
     */
    public static void onPlayerEvent(PlayerLoggedOutEvent e) {
        System.out.println("Removing uuid from disconnected player!");
        latestPos.remove(e.player.getUniqueID());
    }

    public static GeographicProjection projection;
}