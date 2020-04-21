package io.github.terra121;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.terra121.utils.SetBlockingQueue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.terra121.dataset.OpenStreetMaps.*;
import static java.lang.Thread.sleep;
import static net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import static net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;

/**
 * A class that loads the entire region depending on the current player position.
 *
 * @author z3nth10n
 */
@Mod.EventBusSubscriber
public class PlayerRegionDispatcher {
    private static final Map<UUID, CubePos> latestPos = new HashMap<>();
    private static final Set<Coord> processedSet = ConcurrentHashMap.newKeySet();
    private static final Set<Coord> preprocessedSet = ConcurrentHashMap.newKeySet();

    private static final Map<Coord, Optional<Set<Edge>>> mappedEdges = new ConcurrentHashMap<>();

    private static final DispatcherRunnable runnable;
    private static final Thread dispatcherThread;

    /**
     * Start the static context for this class.
     */
    static {
        System.out.println("Created runnable");

        runnable = new DispatcherRunnable();
        dispatcherThread = new Thread(runnable, "t121_dispatcher");
        dispatcherThread.start();
    }

    public static class DispatcherRunnable implements Runnable {
        private final SetBlockingQueue<Optional<Coord>> unprocessedRegions = new SetBlockingQueue<>();

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
            Optional<Coord> curRegion = unprocessedRegions.take();
            if (!curRegion.isPresent()) {
                return false;
            }

            Coord localReg = curRegion.get();
            Set<Edge> set = EarthTerrainProcessor.osm.chunkStructures(localReg.x, localReg.y);
            mappedEdges.put(localReg, set == null ? Optional.empty() : Optional.of(set));

            return true;
        }

        /**
         * Add region to the current runnable.
         * @param cubeX
         * @param cubeZ
         */
        public void addRegion(int cubeX, int cubeZ) {
            unprocessedRegions.add(Optional.of(toCoord(cubeX, cubeZ)));
        }

        /**
         * Check if
         * @param cubeX
         * @param cubeZ
         * @return
         */
        public boolean isBusy(int cubeX, int cubeZ) {
            return unprocessedRegions.contains(Optional.of(toCoord(cubeX, cubeZ)));
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

            CubePos pos = CubePos.fromBlockCoords((int) pX, (int) pY, (int) pZ);
            pos = new CubePos(pos.getX(), 0, pos.getZ());
            // boolean forceGenerating = latestPos == null;

            if (pos != lPos && lPos != null) {
                // TODO
//                int diffX = pos.getX() - (lPos == null ? 0 : lPos.getX());
//                int diffZ = pos.getZ() - (lPos == null ? 0 : lPos.getZ());

                /*
                for (int x = -16; x < 16; ++x) {
                    for (int z = -16; z < 16; ++z) {
                        int cx = pos.getX() + x, cz = pos.getZ() + z;
                        if (isGenerated(cx, cz)) continue;
                        runnable.addRegion(cx, cz);
                    }
                }
                */

                if(!isGenerated(pos.getX(), pos.getZ()) && !runnable.isBusy(pos.getX(), pos.getZ())) {
                    System.out.println("["+pos+"] generating from moving! ["+uuid+"]");
                    runnable.addRegion(pos.getX(), pos.getZ());
                }
            }

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

    /**
     * Call this in order to obtain the generated edges.
     * Also, this is a sync call if any cube is pre-generated before.
     * @param cubeX
     * @param cubeZ
     * @return
     */
    public static Set<Edge> getEdge(int cubeX, int cubeZ) {
        if (isBusy(cubeX, cubeZ)) {
            System.out.println("isBusy");

            // Block sync side until dispatcher has results
            // TODO: attempts
            try {
                do {
                    sleep(50);
                } while (isBusy(cubeX, cubeZ));
            } catch (InterruptedException e) {
                TerraMod.LOGGER.error("Error occurred while waiting sync.", e);
                return null;
            }
        }

        System.out.println("Getting async generated region!");
        Optional<Set<Edge>> val = mappedEdges.get(toCoord(cubeX, cubeZ));
        return val.orElse(null);
    }

    /**
     * Check if the current cube position is generated.
     * @param cubeX
     * @param cubeZ
     * @return
     */
    public static boolean isGenerated(int cubeX, int cubeZ) {
        return processedSet.contains(toCoord(cubeX, cubeZ));
    }

    /**
     * Check if the current region is already being processed by the dispatcher.
     *
     * @return
     */
    public static boolean isBusy(int cubeX, int cubeZ) {
        return preprocessedSet.contains(toCoord(cubeX, cubeZ));
    }

    /**
     * Convert cubeX, cubeZ to int[2]
     * @todo Create class for this.
     * @param x
     * @param z
     * @return
     */
    private static Coord toCoord(int x, int z) {
        return new Coord(x, z);
    }

    public static void addPreregion(int cubeX, int cubeZ) {
        debug("Added", "pre", cubeX, cubeZ);
        preprocessedSet.add(toCoord(cubeX, cubeZ));
    }

    public static void addRegion(int cubeX, int cubeZ) {
        debug("Added", "", cubeX, cubeZ);
        processedSet.add(toCoord(cubeX, cubeZ));
    }

    public static void removePreregion(int cubeX, int cubeZ) {
        debug("Removed", "pre", cubeX, cubeZ);
        preprocessedSet.remove(toCoord(cubeX, cubeZ));
    }

    public static void removeRegion(int cubeX, int cubeZ) {
        debug("Removed", "", cubeX, cubeZ);
        processedSet.remove(toCoord(cubeX, cubeZ));
    }

    private static void debug(String action, String regionType, int cubeX, int cubeZ) {
        System.out.println(action+" "+regionType+"region ("+cubeX+", "+cubeZ+")!");
    }
}
