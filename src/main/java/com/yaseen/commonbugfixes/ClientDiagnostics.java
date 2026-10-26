package com.yaseen.commonbugfixes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-side half of the diagnostics: frame hitches, client heartbeat, sounds, client entity scan. */
@Mod.EventBusSubscriber(modid = CommonBugfixes.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientDiagnostics {
    private static long previousFrameNs = System.nanoTime();
    private static long frameNo;
    private static long clientTicks;
    private static boolean joinedWorld;
    private static boolean clientSessionStarted;
    private static long lastFrameHitchLog;
    private static long lastSecondSnapshotNs;
    private static volatile long lastClientTickNs = System.nanoTime();
    private static volatile long lastRenderNs = System.nanoTime();
    private static volatile Thread clientThread;
    private static volatile String clientContext = "client_not_started";
    private static final AtomicBoolean clientWatchdogsStarted = new AtomicBoolean(false);
    private static final AtomicBoolean clientWatchdogsRunning = new AtomicBoolean(true);
    private static final EntitySnapshotTracker clientEntityTracker = new EntitySnapshotTracker();

    private ClientDiagnostics() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        clientThread = Thread.currentThread();
        lastClientTickNs = now;
        StallWatchdog.gamePaused = mc.isPaused();
        clientContext = "ticks=" + clientTicks + " fps=" + mc.getFps()
                + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                + " world=" + (mc.level == null ? "none" : mc.level.dimension().location());

        // the client log session (and its two watchdogs) start on the first client tick
        if (!clientSessionStarted || !DiagnosticsLog.isOpen()) {
            DiagnosticsLog.start(mc.gameDirectory.toPath(), "client");
            DiagnosticsLog.log("CLIENT_START mc=1.20.1 forge=47.x");
            clientSessionStarted = true;
            if (clientWatchdogsStarted.compareAndSet(false, true)) {
                StallWatchdog.start("client_tick", clientWatchdogsRunning,
                        () -> lastClientTickNs, () -> clientContext, () -> clientThread, 2000L);
                StallWatchdog.start("client_render", clientWatchdogsRunning,
                        () -> lastRenderNs, () -> clientContext, () -> clientThread, 3000L);
            }
        }

        LocalPlayer player = mc.player;
        if (player != null && mc.level != null && !joinedWorld) {
            joinedWorld = true;
            DiagnosticsLog.log("CLIENT_WORLD_JOIN dimension=" + mc.level.dimension().location()
                    + " server=" + (mc.getCurrentServer() == null ? "singleplayer_or_unknown" : mc.getCurrentServer().ip));
            logLoadedMods();
        } else if ((player == null || mc.level == null) && joinedWorld) {
            DiagnosticsLog.log("CLIENT_WORLD_LEAVE");
            joinedWorld = false;
        }

        clientTicks++;
        // one heartbeat + entity scan per second
        if (lastSecondSnapshotNs == 0L || now - lastSecondSnapshotNs >= 1_000_000_000L) {
            lastSecondSnapshotNs = now;
            logClientSecondSnapshot(mc, player);
        }
    }

    private static void logClientSecondSnapshot(Minecraft mc, LocalPlayer player) {
        DiagnosticsLog.checkMemory();
        DiagnosticsLog.log("CLIENT_HEARTBEAT ticks=" + clientTicks + " fps=" + mc.getFps()
                + " render_distance=" + mc.options.renderDistance().get()
                + " simulation_distance=" + mc.options.simulationDistance().get()
                + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                + " world=" + (mc.level == null ? "none" : mc.level.dimension().location())
                + " " + DiagnosticsLog.runtimeStats());

        if (player != null) {
            DiagnosticsLog.log("CLIENT_PLAYER_SNAPSHOT " + CommonBugfixes.playerDescription(player)
                    + " health=" + fmt(player.getHealth())
                    + " food=" + player.getFoodData().getFoodLevel()
                    + " saturation=" + fmt(player.getFoodData().getSaturationLevel())
                    + " air=" + player.getAirSupply()
                    + " xp=" + player.totalExperience
                    + " selected_slot=" + player.getInventory().selected
                    + " inventory=" + CommonBugfixes.playerInventoryDescription(player));
        }

        if (mc.level != null) {
            long count = 0L;
            String dimension = mc.level.dimension().location().toString();
            clientEntityTracker.begin("client", System.nanoTime(), "dimension=" + dimension);
            for (Entity entity : mc.level.entitiesForRendering()) {
                count++;
                clientEntityTracker.record("client", entity);
            }
            clientEntityTracker.end("client");
            DiagnosticsLog.log("WORLD_SNAPSHOT side=client dimension=" + dimension + " entities=" + count);
        }
    }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        lastRenderNs = now;
        long ms = (now - previousFrameNs) / 1_000_000L;
        previousFrameNs = now;
        frameNo++;
        // log frame hitches (>= 100 ms), but not on two consecutive frames
        if (ms >= 100L && frameNo - lastFrameHitchLog >= 2L) {
            lastFrameHitchLog = frameNo;
            Minecraft mc = Minecraft.getInstance();
            DiagnosticsLog.log("CLIENT_HITCH frame=" + frameNo + " duration_ms=" + ms + " fps=" + mc.getFps()
                    + " player=" + (mc.player == null ? "none" : CommonBugfixes.playerDescription(mc.player))
                    + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName()));
        }
    }

    @SubscribeEvent
    public static void sound(PlaySoundEvent event) {
        if (event.getSound() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        SoundInstance sound = event.getSound();
        double dx = sound.getX() - mc.player.getX();
        double dy = sound.getY() - mc.player.getY();
        double dz = sound.getZ() - mc.player.getZ();
        String id = String.valueOf(sound.getLocation());
        String guess = id.contains(":") ? id.substring(0, id.indexOf(':')) : "unknown";
        DiagnosticsLog.log("SOUND_PLAY id=" + id + " namespace_guess=" + guess
                + " category=" + sound.getSource()
                + " sound_pos=" + fmt(sound.getX()) + "," + fmt(sound.getY()) + "," + fmt(sound.getZ())
                + " distance_to_player=" + fmt(Math.sqrt(dx * dx + dy * dy + dz * dz))
                + " player_pos=" + fmt(mc.player.getX()) + "," + fmt(mc.player.getY()) + "," + fmt(mc.player.getZ()));
    }

    private static void logLoadedMods() {
        DiagnosticsLog.log("CLIENT_MOD_LIST_BEGIN count=" + ModList.get().getMods().size());
        ModList.get().getMods().stream()
                .sorted(Comparator.comparing((IModInfo m) -> m.getModId()))
                .forEach(m -> DiagnosticsLog.log("MOD_LOADED id=" + m.getModId()
                        + " name=" + DiagnosticsLog.clean(m.getDisplayName())
                        + " version=" + DiagnosticsLog.clean(m.getVersion().toString())));
        DiagnosticsLog.log("CLIENT_MOD_LIST_END");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
