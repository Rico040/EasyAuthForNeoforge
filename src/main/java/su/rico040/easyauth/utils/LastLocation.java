package su.rico040.easyauth.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public class LastLocation {

    public ServerLevel dimension;
    public Vec3 position;
    public float yaw;
    public float pitch;

    public String toString() {
        return String.format("LastLocation{dimension=%s, position=%s, yaw=%s, pitch=%s}", dimension, position,
                yaw, pitch);
    }
}
