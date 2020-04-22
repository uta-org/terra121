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
public class PlayerDispatcher {
    private static final Map<UUID, CubePos> latestPos = new HashMap<>();
    private static Region[][] regions = new Region[3][3];
    private static final Map<UUID, Region> latestRegion = new HashMap<>();
    private static RegionRunnable runnable;
    private static OpenStreetMaps mapsObj;

    /**
     * Creates the dispatcher instance.
     * @param maps The OSM object.
     */
    public static void init(OpenStreetMaps maps) {
        if (runnable != null) return;

        System.out.println("Created runnable");

        runnable = new RegionRunnable(maps);
        mapsObj = maps; // TODO: References
        Thread dispatcherThread = new Thread(runnable, "Region Dispatcher");
        dispatcherThread.start();
    }

    /**
     * A runnable that works for regions.
     */
    public static class RegionRunnable implements Runnable {
        private final SetBlockingQueue<Optional<Region>> unprocessedRegions = new SetBlockingQueue<>();
        private final Set<Coord> generatedRegions = Sets.newConcurrentHashSet();

        private OpenStreetMaps maps;

        @SuppressWarnings("unused")
        private RegionRunnable() {
        }

        public RegionRunnable(OpenStreetMaps maps) {
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
         *
         * @return
         * @throws InterruptedException
         */
        public boolean tick()
                throws InterruptedException {
            Optional<Region> curRegion = unprocessedRegions.take();
            if (!curRegion.isPresent()) {
                return false;
            }

            Region region = curRegion.get();
            maps.regionDownload(region);

            unprocessedRegions.remove(curRegion);
            generatedRegions.add(region.getCenter());

            return true;
        }

        /**
         * Adds a region based on the grid position.
         * @param dx delta x (expected from -1 to 1)
         * @param dz delta z (expected from -1 to 1)
         */
        public void addRegion(int dx, int dz) {
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) throw new IllegalArgumentException();
            unprocessedRegions.add(Optional.of(getRegion(dx, dz)));
        }

        /**
         * Checks if the current region is being downloaded.
         * @param dx delta x (expected from -1 to 1)
         * @param dz delta z (expected from -1 to 1)
         * @return true if the current region is being downloaded.
         */
        public boolean isBusy(int dx, int dz) {
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) throw new IllegalArgumentException();
            return unprocessedRegions.contains(Optional.of(getRegion(dx, dz)));
        }

        private Region getRegion(int dx, int dz) {
            return regions[dx + 1][dz + 1];
        }

        /**
         * Checks if the current region was already downloaded.
         * @param x custom x
         * @param z custom z
         * @return true if the region was already downloaded.
         */
        public boolean isGenerated(int x, int z) {
            return isGenerated(new Coord(x, z));
        }

        /**
         * Checks if the current region was already downloaded.
         * @param c expected value is the region center (the coord that is stored when a region is downloaded).
         * @return true if the region was already downloaded.
         */
        public boolean isGenerated(Coord c) {
            return generatedRegions.contains(c);
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
    }

    /**
     * Get called on every entity update.
     * Checks if the player moved on a new chunk to pre-generate the current region grid.
     *
     * @param e The event.
     */
    @SubscribeEvent
    public static void onEntityUpdate(LivingUpdateEvent e) {
        // This method is cool, because the player won't move until the region is entire loaded
        if (e.getEntity() instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) e.getEntity();
            UUID uuid = player.getUniqueID();
            CubePos lPos = latestPos.getOrDefault(uuid, null);

            double pX = player.posX;
            double pY = player.posY;
            double pZ = player.posZ;

            CubePos pos = CubePos.fromBlockCoords((int) pX, (int) pY, (int) pZ);
            pos = new CubePos(pos.getX(), 0, pos.getZ());

            if (regions[0][0] == null)
                prepareRegions(pX, pZ);

            boolean wasGridUpdated = false;
            if (pos != lPos && lPos != null) {
                // Update region once per chunk changed
                wasGridUpdated = updateRegions(uuid, pX, pZ);
            }

            // TODO: Work on Edge

            // If the grid was update then send the signal to the runnable in order to download the new regions
            if (wasGridUpdated) {
                for (int x = -1; x <= 1; ++x) {
                    for (int z = -1; z <= 1; ++z) {
                        Region r = regions[x + 1][z + 1];
                        if(!runnable.isGenerated(r.getCenter()) && !runnable.isBusy(x, z)) {
                            runnable.addRegion(x, z);
                        }
                    }
                }
            }

            latestPos.put(uuid, pos);
        }
    }

    /**
     * Prepares the regions.
     * @param pX player x pos
     * @param pZ player z pos
     */
    private static void prepareRegions(double pX, double pZ) {
        // Prepare region
        Region region = createRegion(pX, pZ);

        regions[0][0] = region;

        for (int x = -1; x <= 1; ++x) {
            for (int z = -1; z <= 1; ++z) {
                if (x == 0 && z == 0) continue;
                regions[x + 1][z + 1] = getRegion(x, z);
            }
        }
    }

    /**
     * Gets the region neighbor (on loop call) using the center region (1,1) as relative.
     * @param dx delta x (expected from -1 to 1)
     * @param dz delta z (expected from -1 to 1)
     * @return The region for that delta position on a loop call.
     */
    private static Region getRegion(int dx, int dz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1) throw new IllegalArgumentException();
        return getRegion(dx, dz, 1, 1);
    }

    /**
     * Gets the region neighbor (on loop call) using a custom relative position on the grid.
     * @param dx delta x (expected from -1 to 1)
     * @param dz delta z (expected from -1 to 1)
     * @param relx relative x (expected from -1 to 1)
     * @param relz relative x (expected from -1 to 1)
     * @return A reference region created by the createRegion method.
     */
    private static Region getRegion(int dx, int dz, int relx, int relz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1) throw new IllegalArgumentException();
        Region r = regions[relx][relz];
        OpenStreetMaps.RegionBounds b = r.getBounds();

        int nx = dx > 0 ? b.highX + ICube.SIZE : b.lowX - ICube.SIZE;
        int nz = dz > 0 ? b.highZ + ICube.SIZE : b.lowZ - ICube.SIZE;

        return createRegion(nx, nz);
    }

    /**
     * Create a region reference from a position.
     * @param x a Minecraft world x position.
     * @param z a Minecraft world z position.
     * @return A reference for a region on that world position.
     */
    private static Region createRegion(double x, double z) {
        double[] c = projection.toGeo(x, z);
        Coord coord = OpenStreetMaps.getRegion(c[0], c[1]);
        return new Region(coord, mapsObj.water);
    }

    /**
     * Updates the region grid based on the player position.
     * @param uuid The player uuid.
     * @param pX The player x position.
     * @param pZ The player z position.
     * @return true if the grid was updated.
     */
    private static boolean updateRegions(UUID uuid, double pX, double pZ) {
        Region localPlayerRegion = null;
        int xOffset = 0;
        int yOffset = 0;
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                // Check if we are on a new region
                if ((pX >= regions[x][z].getBounds().lowX) &&
                        (pX <= (regions[x][z].getBounds().highX)) &&
                        (pZ >= regions[x][z].getBounds().lowZ) &&
                        (pZ <= (regions[x][z].getBounds().highZ))) {
                    localPlayerRegion = regions[x][z];
                    xOffset = 1 - x;
                    yOffset = 1 - z;
                    break;
                }
            }

            if (localPlayerRegion != null)
                break;
        }

        // Check if we changed the region
        Region playerRegion = latestRegion.getOrDefault(uuid, null);
        if (localPlayerRegion == null || localPlayerRegion.is(playerRegion))
            return false;

        latestRegion.put(uuid, localPlayerRegion);

        // Re-create the entire grid
        Region[][] newRegions = new Region[3][3];
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++) {
                int newX = x + xOffset;
                if (newX < 0)
                    newX = 2;
                else if (newX > 2)
                    newX = 0;
                int newZ = z + yOffset;
                if (newZ < 0)
                    newZ = 2;
                else if (newZ > 2)
                    newZ = 0;

                newRegions[x][z] = getRegion(x - 1, z - 1, newX, newZ);
            }

        regions = newRegions;
        return true;
    }

    /**
     * Trigger player logout in order to remove the latestPos key.
     *
     * @param e The event.
     */
    public static void onPlayerEvent(PlayerLoggedOutEvent e) {
        System.out.println("Removing uuid from disconnected player!");
        latestPos.remove(e.player.getUniqueID());
    }

    public static GeographicProjection projection;
}