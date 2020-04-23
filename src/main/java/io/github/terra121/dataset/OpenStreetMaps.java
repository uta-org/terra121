package io.github.terra121.dataset;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import io.github.terra121.EarthTerrainProcessor;
import io.github.terra121.PlayerDispatcher;
import io.github.terra121.events.RegionCacheEvent;
import io.github.terra121.projection.ScaleProjection;
import net.minecraftforge.common.MinecraftForge;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.codec.binary.Hex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.terra121.TerraConfig;
import io.github.terra121.TerraMod;
import io.github.terra121.projection.GeographicProjection;

public class OpenStreetMaps {
    private static final double CHUNK_SIZE = 16.0;
    public static final double TILE_SIZE = 1 / 60.0;//250*(360.0/40075000.0);
    private static final double NOTHING = 0.01;

    private static final String OVERPASS_INSTANCE = "https://overpass-api.de";//"https://overpass.kumi.systems";
    private static final String URL_PREFACE = TerraConfig.serverOverpass + "/api/interpreter?data=[out:json];way(";
    private String URL_A = ")";
    private static final String URL_B = ")%20tags%20qt;(._<;);out%20body%20qt;";
    private static final String URL_C = "is_in(";
    @SuppressWarnings("FieldCanBeLocal")
    private final String URL_SUFFIX = ");area._[~\"natural|waterway\"~\"water|riverbank\"];out%20ids;";

    private final Map<Coord, Set<Edge>> chunks;
    public Map<Coord, Region> regions;
    public Water water;

    private final int numcache = TerraConfig.osmCacheSize;
    private final ArrayList<Edge> allEdges;
    private final Gson gson;

    private final GeographicProjection projection;

    public enum Type {
        IGNORE, ROAD, MINOR, SIDE, MAIN, INTERCHANGE, LIMITEDACCESS, FREEWAY, STREAM, RIVER, BUILDING, RAIL
        // ranges from minor to freeway for roads, use road if not known
    }

    public enum Attributes {
        ISBRIDGE, ISTUNNEL, NONE
    }

    public static class noneBoolAttributes {
        public static String layer;
    }

    Type wayType;
    byte wayLanes;

    boolean doRoad;
    boolean doWater;
    boolean doBuildings;

    public OpenStreetMaps(GeographicProjection proj, boolean doRoad, boolean doWater, boolean doBuildings) {
        gson = new GsonBuilder().create();
        chunks = Collections.synchronizedMap(new LinkedHashMap<>());
        allEdges = new ArrayList<>();
        regions = Collections.synchronizedMap(new LinkedHashMap<>());
        projection = proj;
        try {
            water = new Water(this, 256);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        this.doRoad = doRoad;
        this.doWater = doWater;
        this.doBuildings = doBuildings;

        if (!doBuildings) URL_A += "[!\"building\"]";
        if (!doRoad) URL_A += "[!\"highway\"]";
        if (!doWater) URL_A += "[!\"water\"][!\"natural\"][!\"waterway\"]";
        URL_A += ";out%20geom(";

        PlayerDispatcher.init(this);
    }

    public static Coord getRegion(double lon, double lat) {
        return new Coord((int) Math.floor(lon / TILE_SIZE), (int) Math.floor(lat / TILE_SIZE));
    }

    /**
     * Ensure that all the regions that a chunk can contain was downloaded, for this, check the 4 corners of a chunk.
     *
     * @param x
     * @param z
     * @return
     */
    public Set<Edge> chunkStructures(int x, int z) {
        Coord coord = new Coord(x, z);

        if (chunks.containsKey(coord)) return chunks.get(coord);

        if (regionCache(projection.toGeo(x * CHUNK_SIZE, z * CHUNK_SIZE)) == null)
            return null;

        if (regionCache(projection.toGeo((x + 1) * CHUNK_SIZE - 1, z * CHUNK_SIZE)) == null)
            return null;

        if (regionCache(projection.toGeo((x + 1) * CHUNK_SIZE - 1, (z + 1) * CHUNK_SIZE - 1)) == null)
            return null;

        if (regionCache(projection.toGeo(x * CHUNK_SIZE, (z + 1) * CHUNK_SIZE - 1)) == null)
            return null;

        return chunks.get(coord);
    }

    public Region regionCache(double[] corner) {
        //bound check
        if (!(corner[0] >= -180 && corner[0] <= 180 && corner[1] >= -80 && corner[1] <= 80)) {
            return null;
        }

        // TODO: Do global events for each cube?
        Coord coord = getRegion(corner[0], corner[1]);
        Region region;

        if ((region = regions.get(coord)) == null) {
            if (MinecraftForge.EVENT_BUS.post(new RegionCacheEvent.Pre()))
                return null; // cancelled

            region = new Region(coord, water);
            int i;
            //noinspection StatementWithEmptyBody
            for (i = 0; i < 5 && !regionDownload(region); i++) ;
            regions.put(coord, region);
            if (regions.size() > numcache) {
                //TODO: delete beter
                Iterator<Region> it = regions.values().iterator();
                Region delete = it.next();
                it.remove();
                removeRegion(delete);
            }

            if (i == 5) {
                region.failedDownload = true;
                TerraMod.LOGGER.error("OSM region" + region.coord.x + " " + region.coord.y + " failed to download several times, no structures will spawn");

                MinecraftForge.EVENT_BUS.post(new RegionCacheEvent.Post(region, RegionCacheEvent.FailureType.MAX_ATTEMPTS_EXCEEDED));
                return null;
            }

            MinecraftForge.EVENT_BUS.post(new RegionCacheEvent.Post(region));
        } else if (region.failedDownload) {
            MinecraftForge.EVENT_BUS.post(new RegionCacheEvent.Post(region, RegionCacheEvent.FailureType.FAILED));
            return null; //don't return dummy regions
        }

        return region;
    }

    private String getMD5(String str)
            throws NoSuchAlgorithmException {
        byte[] bytesOfMessage = str.getBytes(StandardCharsets.UTF_8);

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] dig = md.digest(bytesOfMessage);
        return new String(Hex.encodeHex(dig));
    }

    public boolean regionDownload(Region region) {
        double X = region.coord.x * TILE_SIZE;
        double Y = region.coord.y * TILE_SIZE;

        //limit extreme (a.k.a. way too clustered on some projections) requests and out of bounds requests
        if (Y > 80 || Y < -80 || X < -180 || X > 180 - TILE_SIZE) {
            region.failedDownload = true;
            return false;
        }

        try {
            String bottomleft = Y + "," + X;
            String bbox = bottomleft + "," + (Y + TILE_SIZE) + "," + (X + TILE_SIZE);

            String url = URL_PREFACE + bbox + URL_A + bbox + URL_B;
            if (doWater) url += URL_C + bottomleft + URL_SUFFIX;

            Path filepath = Paths.get(TerraMod.minecraftDir,
                    "geo-data",
                    EarthTerrainProcessor.worldObj.getWorldInfo().getWorldName(),
                    getMD5(url) + ".json");

            Files.createDirectories(filepath.getParent());

            TerraMod.LOGGER.info(url);

            String json;
            File file = filepath.toFile();
            if (file.exists()) {
                json = new String(Files.readAllBytes(filepath), StandardCharsets.UTF_8);
            } else {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    //noinspection ConstantConditions
                    json = response.body().string();
                } catch (IOException e) {
                    TerraMod.LOGGER.error("Osm region failed on OkHttpClient request", e);
                    return false;
                }

                Files.write(filepath, json.getBytes(StandardCharsets.UTF_8));
            }

            doGson(json, region);
        } catch (Exception e) {
            TerraMod.LOGGER.error("Osm region download failed, no osm features will spawn", e);
            return false;
        }

        double[] ll = projection.fromGeo(X, Y);
        double[] lr = projection.fromGeo(X + TILE_SIZE, Y);
        double[] ur = projection.fromGeo(X + TILE_SIZE, Y + TILE_SIZE);
        double[] ul = projection.fromGeo(X, Y + TILE_SIZE);

        //estimate bounds of region in terms of chunks
        int lowX = (int) Math.floor(Math.min(Math.min(ll[0], ul[0]), Math.min(lr[0], ur[0])) / CHUNK_SIZE);
        int highX = (int) Math.ceil(Math.max(Math.max(ll[0], ul[0]), Math.max(lr[0], ur[0])) / CHUNK_SIZE);
        int lowZ = (int) Math.floor(Math.min(Math.min(ll[1], ul[1]), Math.min(lr[1], ur[1])) / CHUNK_SIZE);
        int highZ = (int) Math.ceil(Math.max(Math.max(ll[1], ul[1]), Math.max(lr[1], ur[1])) / CHUNK_SIZE);

        for (Edge e : allEdges)
            relevantChunks(lowX, lowZ, highX, highZ, e);
        allEdges.clear();

        return true;
    }

    private void doGson(String str, Region region) {
        Data data = gson.fromJson(str, Data.class);

        Map<Long, Element> allWays = new HashMap<>();
        Set<Element> unusedWays = new HashSet<>();
        Set<Long> ground = new HashSet<>();

        for (Element elem : data.elements) {
            Attributes attributes = Attributes.NONE;
            if (elem.type == EType.way) {
                allWays.put(elem.id, elem);

                if (elem.tags == null) {
                    unusedWays.add(elem);
                    continue;
                }

                String naturalv = null, highway = null, waterway = null, building = null, istunnel = null, isbridge = null;

                if (doWater) {
                    naturalv = elem.tags.get("natural");
                    waterway = elem.tags.get("waterway");
                }

                if (doRoad) {
                    highway = elem.tags.get("highway");
                    istunnel = elem.tags.get("tunnel");
                    // to be implemented
                    isbridge = elem.tags.get("bridge");
                }

                if (doBuildings) {
                    building = elem.tags.get("building");
                }

                if (naturalv != null && naturalv.equals("coastline")) {
                    waterway(elem, -1, region, null);
                } else if (highway != null || (waterway != null && (waterway.equals("river") ||
                        waterway.equals("canal") || waterway.equals("stream"))) || building != null) { //TODO: fewer equals

                    Type type = Type.ROAD;

                    if (waterway != null) {
                        type = Type.STREAM;
                        if (waterway.equals("river") || waterway.equals("canal"))
                            type = Type.RIVER;

                    }

                    if (building != null) type = Type.BUILDING;

                    if (istunnel != null && istunnel.equals("yes")) {

                        attributes = Attributes.ISTUNNEL;

                    } else if (isbridge != null && isbridge.equals("yes")) {

                        attributes = Attributes.ISBRIDGE;

                    } else {

                        // totally skip classification if it's a tunnel or bridge. this should make it more efficient.
                        if (highway != null && attributes == Attributes.NONE) {
                            switch (highway) {
                                case "motorway":
                                    type = Type.FREEWAY;
                                    break;
                                case "trunk":
                                    type = Type.LIMITEDACCESS;
                                    break;
                                case "motorway_link":
                                case "trunk_link":
                                    type = Type.INTERCHANGE;
                                    break;
                                case "secondary":
                                    type = Type.SIDE;
                                    break;
                                case "primary":
                                case "raceway":
                                    type = Type.MAIN;
                                    break;
                                case "tertiary":
                                case "residential":
                                    type = Type.MINOR;
                                    break;
                                default:
                                    if (highway.equals("primary_link") ||
                                            highway.equals("secondary_link") ||
                                            highway.equals("living_street") ||
                                            highway.equals("bus_guideway") ||
                                            highway.equals("service") ||
                                            highway.equals("unclassified"))
                                        type = Type.SIDE;
                                    break;
                            }
                        }
                    }
                    //get lane number (default is 2)
                    String slanes = elem.tags.get("lanes");
                    String slayer = elem.tags.get("layers");
                    byte lanes = 2;
                    byte layer = 1;

                    if (slayer != null) {

                        try {

                            layer = Byte.parseByte(slayer);

                        } catch (NumberFormatException e) {

                            // default to layer 1 if bad format

                        }

                    }

                    if (slanes != null) {

                        try {

                            lanes = Byte.parseByte(slanes);

                        } catch (NumberFormatException e) {

                        } //default to 2, if bad format
                    }

                    //prevent super high # of lanes to prevent ridiculous results (prly a mistake if its this high anyways)
                    if (lanes > 8)
                        lanes = 8;

                    // an interchange that doesn't have any lane tag should be defaulted to 2 lanes
                    if (lanes < 2 && type == Type.INTERCHANGE) {
                        lanes = 2;
                    }

                    // upgrade road type if many lanes (and the road was important enough to include a lanes tag)
                    if (lanes > 2 && type == Type.MINOR)
                        type = Type.MAIN;

                    addWay(elem, type, lanes, region, attributes, layer);
                } else unusedWays.add(elem);
            } else if (elem.type == EType.relation && elem.members != null && elem.tags != null) {

                if (doWater) {
                    String naturalv = elem.tags.get("natural");
                    String waterv = elem.tags.get("water");
                    String wway = elem.tags.get("waterway");

                    if (waterv != null || (naturalv != null && naturalv.equals("water")) || (wway != null && wway.equals("riverbank"))) {
                        for (Member member : elem.members) {
                            if (member.type == EType.way) {
                                Element way = allWays.get(member.ref);
                                if (way != null) {
                                    waterway(way, elem.id + 3600000000L, region, null);
                                    unusedWays.remove(way);
                                }
                            }
                        }
                        continue;
                    }
                }
                if (doBuildings && elem.tags.get("building") != null) {
                    for (Member member : elem.members) {
                        if (member.type == EType.way) {
                            Element way = allWays.get(member.ref);
                            if (way != null) {
                                addWay(way, Type.BUILDING, (byte) 1, region, Attributes.NONE, (byte) 0);
                                unusedWays.remove(way);
                            }
                        }
                    }
                }

            } else if (elem.type == EType.area) {
                ground.add(elem.id);
            }
        }

        if (doWater) {

            for (Element way : unusedWays) {
                if (way.tags != null) {
                    String naturalv = way.tags.get("natural");
                    String waterv = way.tags.get("water");
                    String wway = way.tags.get("waterway");

                    if (waterv != null || (naturalv != null && naturalv.equals("water")) || (wway != null && wway.equals("riverbank")))
                        waterway(way, way.id + 2400000000L, region, null);
                }
            }

            if (water.grounding.state(region.coord.x, region.coord.y) == 0) {
                ground.add(-1L);
            }

            region.renderWater(ground);
        }
    }

    void addWay(Element elem, Type type, byte lanes, Region region, Attributes attributes, byte layer) {
        double[] lastProj = null;
        if (elem.geometry != null)
            for (Geometry geom : elem.geometry) {
                if (geom == null) lastProj = null;
                else {
                    double[] proj = projection.fromGeo(geom.lon, geom.lat);

                    if (lastProj != null) { //register as a road edge
                        allEdges.add(new Edge(lastProj[0], lastProj[1], proj[0], proj[1], type, lanes, region, attributes, layer));
                    }

                    lastProj = proj;
                }
            }
    }

    Geometry waterway(Element way, long id, Region region, Geometry last) {
        if (way.geometry != null)
            for (Geometry geom : way.geometry) {
                if (geom != null && last != null) {
                    region.addWaterEdge(last.lon, last.lat, geom.lon, geom.lat, id);
                }
                last = geom;
            }

        return last;
    }

    private void relevantChunks(int lowX, int lowZ, int highX, int highZ, Edge edge) {
        Coord start = new Coord((int) Math.floor(edge.slon / CHUNK_SIZE), (int) Math.floor(edge.slat / CHUNK_SIZE));
        Coord end = new Coord((int) Math.floor(edge.elon / CHUNK_SIZE), (int) Math.floor(edge.elat / CHUNK_SIZE));

        double startx = edge.slon;
        double endx = edge.elon;

        if (startx > endx) {
            Coord tmp = start;
            start = end;
            end = tmp;
            startx = endx;
            endx = edge.slon;
        }

        highX = Math.min(highX, end.x + 1);
        for (int x = Math.max(lowX, start.x); x < highX; x++) {
            double X = x * CHUNK_SIZE;
            int from = (int) Math.floor((edge.slope * Math.max(X, startx) + edge.offset) / CHUNK_SIZE);
            int to = (int) Math.floor((edge.slope * Math.min(X + CHUNK_SIZE, endx) + edge.offset) / CHUNK_SIZE);

            if (from > to) {
                int tmp = from;
                from = to;
                to = tmp;
            }

            for (int y = Math.max(from, lowZ); y <= to && y < highZ; y++) {
                associateWithChunk(new Coord(x, y), edge);
            }
        }
    }

    private void associateWithChunk(Coord c, Edge edge) {
        Set<Edge> list = chunks.computeIfAbsent(c, k -> new HashSet<>());
        list.add(edge);
    }

    //TODO: this algorithm is untested and may have some memory leak issues and also strait up copies code from earlier
    private void removeRegion(Region delete) {
        RegionBounds b = RegionBounds.getBounds(projection, delete);

        for (int x = b.lowX; x < b.highX; x++) {
            for (int z = b.lowZ; z < b.highZ; z++) {
                Set<Edge> edges = chunks.get(new Coord(x, z));
                if (edges != null) {
                    Iterator<Edge> it = edges.iterator();
                    while (it.hasNext())
                        if (it.next().region.equals(delete))
                            it.remove();

                    if (edges.size() <= 0)
                        chunks.remove(new Coord(x, z));
                }
            }
        }
    }

    public static class RegionBounds {
        public int lowX;
        public int highX;
        public int lowZ;
        public int highZ;

        public Coord coord;

        public RegionBounds(int lowX, int highX, int lowZ, int highZ) {
            this.lowX = lowX;
            this.highX = highX;
            this.lowZ = lowZ;
            this.highZ = highZ;
        }

        public int hashCode() {
            return (lowX * 79399) + (highX * 56789) + (lowZ * 98765) + (highZ * 100000);
        }

        public boolean equals(Object o) {
            RegionBounds b = (RegionBounds) o;
            return b.lowX == lowX && b.highX == highX && b.lowZ == lowZ && b.highZ == highZ;
        }

        public String toString() {
            return "x: [" + lowX + ", " + highX + "] || z: [" + highX + ", " + highZ + "]";
        }

        public String toStringPretty() {
            int spacesX = Integer.toString(highX).length() - Integer.toString(lowX).length();
            int spacesZ = Integer.toString(highZ).length() - Integer.toString(lowZ).length();
            return "x: [" + getSpaces(spacesX) + lowX + ", " + highX + "] || z: [" + getSpaces(spacesZ) + lowZ + ", " + highZ + "]";
        }

        private String getSpaces(int n) {
            String r = "";
            for (int i = 0; i < n; ++i) {
                r += " ";
            }
            return r;
        }

        // Used at removing regions
        public static RegionBounds getBounds(GeographicProjection projection, Region region) {
            return getBounds(projection, region.coord.x, region.coord.y, true, true, true);
        }

        public static RegionBounds getBounds(GeographicProjection projection, double x, double y) {
            double[] c = projection.toGeo(x, y);
            return getBounds(projection, c);
        }

        public static RegionBounds getBounds(GeographicProjection projection, double[] corner) {
            return getBounds(projection, corner[0], corner[1], false, false, false);
        }

        public static RegionBounds getBounds(GeographicProjection projection, Coord coord) {
            return getBounds(projection, coord.x, coord.y, true, false, false);
        }

        private static RegionBounds getBounds(GeographicProjection projection, double x, double y, boolean isConverted, boolean useChunksSize, boolean keepInverted) {
            Coord regionCoord = getRegion(x, y);

            double rx = isConverted ? x : regionCoord.x;
            double ry = isConverted ? y : regionCoord.y;

            double X = rx * TILE_SIZE;
            double Y = ry * TILE_SIZE;

            double[] ll = projection.fromGeo(X, Y);
            double[] lr = projection.fromGeo(X + TILE_SIZE, Y);
            double[] ur = projection.fromGeo(X + TILE_SIZE, Y + TILE_SIZE);
            double[] ul = projection.fromGeo(X, Y + TILE_SIZE);

            int ix = 0;
            int iz = 1;

            if(projection instanceof ScaleProjection && ((ScaleProjection)projection).isInverted() && !keepInverted) {
                ix = 1;
                iz = 0;
            }

            //estimate bounds of region in terms of blocks
            double _lowX = Math.min(Math.min(ll[ix], ul[ix]), Math.min(lr[ix], ur[ix]));
            double _highX = Math.max(Math.max(ll[ix], ul[ix]), Math.max(lr[ix], ur[ix]));
            double _lowZ = Math.min(Math.min(ll[iz], ul[iz]), Math.min(lr[iz], ur[iz]));
            double _highZ = Math.max(Math.max(ll[iz], ul[iz]), Math.max(lr[iz], ur[iz]));

            int lowX;
            int highX;
            int lowZ;
            int highZ;

            // then, get the bounds in terms of chunks if needed
            if(useChunksSize) {
                lowX = (int) Math.floor(_lowX / CHUNK_SIZE);
                highX = (int) Math.ceil(_highX / CHUNK_SIZE);
                lowZ = (int) Math.ceil(_lowZ / CHUNK_SIZE);
                highZ = (int) Math.ceil(_highZ / CHUNK_SIZE);
            } else {
                lowX = (int)_lowX;
                highX = (int)_highX;
                lowZ = (int)_lowZ;
                highZ = (int)_highZ;
            }

            // Ensure bounds
            RegionBounds b = new RegionBounds(Math.min(lowX, highX), Math.max(highX, lowX), Math.min(lowZ, highZ), Math.max(highZ, lowZ));
            b.coord = regionCoord;
            return b;
        }
    }

    //integer coordinate class
    public static class Coord {
        public int x;
        public int y;

        public Coord(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int hashCode() {
            return (x * 79399) + (y * 100000);
        }

        public boolean equals(Object o) {
            Coord c = (Coord) o;
            return c.x == x && c.y == y;
        }

        public String toString() {
            return "(" + x + ", " + y + ")";
        }

        public String toStringPretty() {
            return "(" + (x >= 0 ? " " + x : x) + ", " + (y >= 0 ? " " + y : y) + ")";
        }

        public static Coord getZero() {
            return new Coord(0, 0);
        }
    }

    public static class Edge {
        public Type type;
        public double slat;
        public double slon;
        public double elat;
        public double elon;
        public Attributes attribute;
        public byte layer_number;
        public double slope;
        public double offset;

        public byte lanes;

        Region region;

        private double squareLength() {
            double dlat = elat - slat;
            double dlon = elon - slon;
            return dlat * dlat + dlon * dlon;
        }

        private Edge(double slon, double slat, double elon, double elat, Type type, byte lanes, Region region, Attributes att, byte ly) {
            //slope must not be infinity, slight inaccuracy shouldn't even be noticible unless you go looking for it
            double dif = elon - slon;
            if (-NOTHING <= dif && dif <= NOTHING) {
                if (dif < 0) {
                    elon -= NOTHING;
                } else {
                    elon += NOTHING;
                }
            }

            this.slat = slat;
            this.slon = slon;
            this.elat = elat;
            this.elon = elon;
            this.type = type;
            this.attribute = att;
            this.lanes = lanes;
            this.region = region;
            this.layer_number = ly;

            slope = (elat - slat) / (elon - slon);
            offset = slat - slope * slon;
        }

        public int hashCode() {
            return (int) ((slon * 79399) + (slat * 100000) + (elat * 13467) + (elon * 103466));
        }

        public boolean equals(Object o) {
            Edge e = (Edge) o;
            return e.slat == slat && e.slon == slon && e.elat == elat && e.elon == e.elon;
        }

        public String toString() {
            return "(" + slat + ", " + slon + "," + elat + "," + elon + ")";
        }
    }

    public static enum EType {
        invalid, node, way, relation, area
    }

    public static class Member {
        EType type;
        long ref;
        String role;
    }

    public static class Geometry {
        double lat;
        double lon;
    }

    public static class Element {
        EType type;
        long id;
        Map<String, String> tags;
        long[] nodes;
        Member[] members;
        Geometry[] geometry;
    }

    public static class Data {
        float version;
        String generator;
        Map<String, String> osm3s;
        List<Element> elements;
    }

    public static void main(String[] args) {
    }
}