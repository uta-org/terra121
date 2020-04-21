package io.github.terra121;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.terra121.dataset.OpenStreetMaps;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.sleep;
import static net.minecraftforge.event.entity.living.LivingEvent.*;
import static net.minecraftforge.fml.common.gameevent.PlayerEvent.*;

/**
 * A class that loads the entire region depending on the current player position.
 *
 * @author z3nth10n
 */
@Mod.EventBusSubscriber
public class PlayerRegionDispatcher {
    private static Map<UUID, CubePos> latestPos = new HashMap<>();
    public static Set<int[]> processedSet = new HashSet<>();
    public static Set<int[]> preprocessedSet = new HashSet<>();

    private static final Map<int[], Set<OpenStreetMaps.Edge>> mappedEdges = new ConcurrentHashMap<>();

    private static final DispatcherRunnable runnable;
    private static final Thread dispatcherThread;

    /**
     * Start the static context for this class.
     */
    static {
        runnable = new DispatcherRunnable();
        dispatcherThread = new Thread(runnable, "t121_dispatcher");
        dispatcherThread.start();
    }

    public static class DispatcherRunnable implements Runnable {
        private final BlockingQueue<Optional<int[]>> unprocessedRegions = new LinkedBlockingQueue<>();

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
                e.printStackTrace();
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
            Optional<int[]> curRegion = unprocessedRegions.take();
            if (!curRegion.isPresent()) {
                return false;
            }

            int[] localReg = curRegion.get();
            Set<OpenStreetMaps.Edge> set = EarthTerrainProcessor.osm.chunkStructures(localReg[0], localReg[1]);
            mappedEdges.put(localReg, set);

            return true;
        }

        /**
         * Add region to the current runnable.
         * @param cubeX
         * @param cubeZ
         */
        public void addRegion(int cubeX, int cubeZ) {
            unprocessedRegions.add(Optional.of(toGeo(cubeX, cubeZ)));
        }

        /**
         * Stops the current runnable.
         */
        public void stop() {
            try {
                unprocessedRegions.put(Optional.empty());
            } catch (InterruptedException e) {
                e.printStackTrace();
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

            if (pos != lPos) {
                // TODO
//                int diffX = pos.getX() - (lPos == null ? 0 : lPos.getX());
//                int diffZ = pos.getZ() - (lPos == null ? 0 : lPos.getZ());

                for (int x = -16; x < 16; ++x) {
                    for (int z = -16; z < 16; ++z) {
                        int cx = pos.getX() + x, cz = pos.getZ() + z;
                        if (isGenerated(cx, cz)) continue;
                        runnable.addRegion(cx, cz);
                    }
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
        latestPos.remove(e.player.getUniqueID());
    }

    /**
     * Call this in order to obtain the generated edges.
     * Also, this is a sync call if any cube is pre-generated before.
     * @param cubeX
     * @param cubeZ
     * @return
     */
    public static Set<OpenStreetMaps.Edge> getEdge(int cubeX, int cubeZ) {
        if (isBusy(cubeX, cubeZ)) {
            // Block sync side until dispatcher has results
            // TODO: attempts
            try {
                do {
                    sleep(50);
                } while (isBusy(cubeX, cubeZ));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return mappedEdges.get(toGeo(cubeX, cubeZ));
    }

    /**
     * Is the current region not being processed by the dispatcher?
     *
     * @param cubeX
     * @param cubeZ
     * @return
     */
    public static boolean isFree(int cubeX, int cubeZ) {
        return !preprocessedSet.contains(toGeo(cubeX, cubeZ)) && !processedSet.contains(toGeo(cubeX, cubeZ));
    }

    /**
     * Check if the current cube position is generated.
     * @param cubeX
     * @param cubeZ
     * @return
     */
    public static boolean isGenerated(int cubeX, int cubeZ) {
        return processedSet.contains(toGeo(cubeX, cubeZ));
    }

    /**
     * Check if the current region is already being processed by the dispatcher.
     *
     * @return
     */
    public static boolean isBusy(int cubeX, int cubeZ) {
        return preprocessedSet.contains(toGeo(cubeX, cubeZ));
    }

    /**
     * Convert cubeX, cubeZ to int[2]
     * @todo Create class for this.
     * @param x
     * @param y
     * @return
     */
    private static int[] toGeo(int x, int y) {
        return new int[]{x, y};
    }
}
