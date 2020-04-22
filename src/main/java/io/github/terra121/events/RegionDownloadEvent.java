package io.github.terra121.events;

import io.github.terra121.dataset.OpenStreetMaps;
import io.github.terra121.dataset.Region;
import net.minecraftforge.fml.common.eventhandler.Event;

public class RegionDownloadEvent extends Event {
    public enum FailureType { FAILED, MAX_ATTEMPTS_EXCEEDED }
    private FailureType failureType;
    private boolean error;
    private Region region;
    protected OpenStreetMaps.Coord blockCoord;

    private RegionDownloadEvent() {}

    public RegionDownloadEvent(Region region) {
        this.region = region;
    }

    public RegionDownloadEvent(Region region, FailureType failureType) {
        this(region);
        this.error = true;
        this.failureType = failureType;
    }

    public Region getRegion() {
        return region;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public boolean isError() {
        return error;
    }

    public OpenStreetMaps.Coord getBlockCoord() {
        return blockCoord;
    }

    /*
    public void setCorner(double[] corner) {
        this.corner = corner;
    }
    */

    public static class Pre extends RegionDownloadEvent {
        private Pre() {}

        public Pre(OpenStreetMaps.Coord blockCoord) {
            super.blockCoord = blockCoord;
        }
    }

    public static class Post extends RegionDownloadEvent {
        private Post() {}

        public Post(Region region) {
            super(region);
        }

        public Post(Region region, FailureType failureType) {
            super(region, failureType);
        }
    }
}
