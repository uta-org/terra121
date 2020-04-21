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

    static {
        runnable = new DispatcherRunnable();
        dispatcherThread = new Thread(runnable, "t121_dispatcher");
        dispatcherThread.start();
    }

//    public interface IAction<T1, T2> {
//        void execute(T1 t1, T2 t2);
//    }

    public static class DispatcherRunnable implements Runnable {
        private final BlockingQueue<int[]> unprocessedRegions = new LinkedBlockingQueue<>();
        // private IAction<int[], Set<OpenStreetMaps.Edge>> callback;

        // private final Set<int[]> processingRegions = Sets.newConcurrentHashSet();

//        private DispatcherRunnable() {}
//
//        public DispatcherRunnable(IAction<int[], Set<OpenStreetMaps.Edge>> callback) {
//            this.callback = callback;
//        }

        @Override
        public void run() {
            try {
                //noinspection InfiniteLoopStatement
                while (true) {
                    tick();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        public boolean tick()
                throws InterruptedException
        {
            //if (unprocessedRegions.size() > 0) {
            int[] curRegion = unprocessedRegions.take();
            //noinspection ConstantConditions
            if(curRegion == null) {
                return false;
            }

            //processingRegions.add(curRegion);
            {
                Set<OpenStreetMaps.Edge> set = EarthTerrainProcessor.osm.chunkStructures(curRegion[0], curRegion[1]);
                mappedEdges.put(curRegion, set);
            }
            //processingRegions.remove(curRegion);
            //}

            // do sleep?
            //sleep(50);

            return true;
        }

        public void addRegion(int cubeX, int cubeZ) {
            unprocessedRegions.add(toGeo(cubeX, cubeZ));
        }

        public void stop() {
            try {
                //noinspection ConstantConditions
                unprocessedRegions.put(null);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

//        public boolean isProcessing() {
//            return false;
//        }
    }

    /**
     * Get called on every entity update.
     *
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
                // !isGenerated(pos.getX(), pos.getZ())
                // TODO
//                int diffX = pos.getX() - (lPos == null ? 0 : lPos.getX());
//                int diffZ = pos.getZ() - (lPos == null ? 0 : lPos.getZ());

                for (int x = -16; x < 16; ++x) {
                    for(int z = -16; z < 16; ++z) {
                        int cx = pos.getX() + x, cz = pos.getZ() + z;
                        if(isGenerated(cx, cz)) continue;
                        runnable.addRegion(cx, cz);
                    }
                }
            }

            latestPos.put(uuid, pos);
        }
    }

    public static void onPlayerEvent(PlayerLoggedOutEvent e) {
        latestPos.remove(e.player.getUniqueID());
    }

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

    private static int[] toGeo(int x, int y) {
        return new int[]{x, y};
    }
}
