package com.yaseen.commonbugfixes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;

@Mod(CommonBugfixes.MODID)
public class CommonBugfixes {
    public static final String MODID = "common_bugfixes";

    private volatile MinecraftServer server;
    private long tickStartNs;
    private volatile long tickCount;
    private volatile long lastServerTickEndNs;
    private volatile long lastTickDurationMs;
    private volatile String serverContext = "not_started";
    private volatile Thread serverThread;
    private long lastSecondSnapshotNs;
    private volatile AtomicBoolean watchdogRunning = new AtomicBoolean(false);
    private final EntitySnapshotTracker serverEntityTracker = new EntitySnapshotTracker();
    private final long[] recentTickMs = new long[100];
    private int recentTickIndex;
    private int recentTickFilled;
    private static boolean globalHooksInstalled;

    public CommonBugfixes() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DiagnosticsConfig.SPEC, "common_bugfixes-common.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        installGlobalHooks();
    }

    /** The config file is loaded by now, so the analysis can use the configured values. */
    private void commonSetup(FMLCommonSetupEvent event) {
        if (DiagnosticsConfig.analyzeCrashes()) {
            CrashAnalyzer.runStartup(FMLPaths.GAMEDIR.get(), DiagnosticsConfig.crashLogLines());
        }
    }

    /** Records uncaught exceptions in the log and makes sure the end of the log reaches the disk when the JVM exits. */
    private static synchronized void installGlobalHooks() {
        if (globalHooksInstalled) return;
        globalHooksInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                DiagnosticsLog.logThrowable("UNCAUGHT_EXCEPTION thread=" + DiagnosticsLog.clean(thread.getName()), error);
            } catch (Throwable ignored) {
                // never let logging hide the original error
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> DiagnosticsLog.shutdown("jvm_shutdown", 3000L), "CommonBugfixes-Shutdown"));
    }

    // ---------------------------------------------------------------- command

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("commonbugfixes")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    reply(context.getSource(), statusLines());
                    return 1;
                }))
                .then(Commands.literal("lastcrash").executes(context -> {
                    List<String> lines = CrashAnalyzer.lastAnalysisLines;
                    if (lines.isEmpty()) {
                        reply(context.getSource(), List.of("No crash analysis found. Analyses are written to 'Common Bugfixes/crash-analysis'."));
                    } else {
                        List<String> shown = new ArrayList<>();
                        for (String line : lines) {
                            if (line.startsWith("LAST ") || line.startsWith("WARNINGS IN THE LAST")) break;
                            if (!line.isBlank()) shown.add(line);
                            if (shown.size() >= 24) break;
                        }
                        reply(context.getSource(), shown);
                    }
                    return 1;
                })));
    }

    private static void reply(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Common Bugfixes " + version());
        lines.add("Log file: " + (DiagnosticsLog.currentFile() == null ? "none" : DiagnosticsLog.currentFile()));
        if (recentTickFilled > 0) {
            long sum = 0L;
            long max = 0L;
            for (int i = 0; i < recentTickFilled; i++) {
                sum += recentTickMs[i];
                max = Math.max(max, recentTickMs[i]);
            }
            double average = (double) sum / recentTickFilled;
            lines.add(String.format(Locale.ROOT, "Last %d ticks: average %.1f ms, worst %d ms, about %.1f TPS",
                    recentTickFilled, average, max, Math.min(20.0, 1000.0 / Math.max(1.0, average))));
        }
        lines.add("Last entity scan: " + serverEntityTracker.summary());
        lines.add(DiagnosticsLog.runtimeStats());
        return lines;
    }

    /** Version string from mods.toml, used in the log header. */
    static String version() {
        return ModList.get().getModContainerById(MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    // ---------------------------------------------------------------- server lifecycle

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        server = event.getServer();
        serverThread = Thread.currentThread();
        lastServerTickEndNs = System.nanoTime();
        lastSecondSnapshotNs = 0L;
        tickCount = 0L;
        tickStartNs = 0L;
        recentTickIndex = 0;
        recentTickFilled = 0;
        serverContext = "starting world=" + DiagnosticsLog.clean(server.getWorldData().getLevelName());
        Path dir = server.getServerDirectory().toPath();
        DiagnosticsLog.start(dir, "server");
        DiagnosticsLog.log("SERVER_START mc=1.20.1 forge=47.x world=" + DiagnosticsLog.clean(server.getWorldData().getLevelName()));
        logLoadedMods();
        // a fresh flag per server session, so monitors of an earlier session end for good
        final AtomicBoolean running = new AtomicBoolean(true);
        watchdogRunning = running;
        StallWatchdog.start("server_tick", running,
                () -> lastServerTickEndNs, () -> serverContext, () -> serverThread, 2000L);
        // reports what the server thread is doing while a single tick is slow; starts after the first tick
        // so that world loading is not reported
        StallWatchdog.start("server_slow_tick", "SLOW_TICK", running,
                () -> tickCount > 0 ? lastServerTickEndNs : 0L, () -> serverContext, () -> serverThread,
                DiagnosticsConfig.slowTickMs(), DiagnosticsConfig.slowTickFrames());
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        DiagnosticsLog.log("SERVER_READY players=" + server.getPlayerList().getPlayerCount()
                + " view_distance=" + server.getPlayerList().getViewDistance()
                + " simulation_distance=" + server.getPlayerList().getSimulationDistance());
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent event) {
        DiagnosticsLog.log("SERVER_STOP ticks=" + tickCount);
        watchdogRunning.set(false);
        DiagnosticsLog.stop("server_stopping");
        server = null;
        serverContext = "stopped";
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartNs = System.nanoTime();
            serverThread = Thread.currentThread();
            return;
        }
        tickCount++;
        long now = System.nanoTime();
        long ms = tickStartNs == 0L ? 0L : (now - tickStartNs) / 1_000_000L;
        lastTickDurationMs = ms;
        recentTickMs[recentTickIndex] = ms;
        recentTickIndex = (recentTickIndex + 1) % recentTickMs.length;
        if (recentTickFilled < recentTickMs.length) recentTickFilled++;
        lastServerTickEndNs = now;
        serverContext = "tick=" + tickCount + " tick_ms=" + ms;

        if (ms >= 100L) {
            String severity = ms >= 1000L ? "CRITICAL" : ms >= 500L ? "HIGH" : "NOTICE";
            DiagnosticsLog.log("SERVER_HITCH severity=" + severity + " duration_ms=" + ms
                    + " tick=" + tickCount
                    + " tps_est=" + String.format(Locale.ROOT, "%.1f", Math.min(20.0, 1000.0 / Math.max(1L, ms))));
            if (ms >= 500L) {
                DiagnosticsLog.log("HITCH_CONTEXT tick=" + tickCount + " duration_ms=" + ms + " last_entity_scan=" + serverEntityTracker.summary());
            }
            if (ms >= 1000L) logServerSnapshot("HITCH");
        }

        // one heartbeat + entity scan per second
        if (server != null && (lastSecondSnapshotNs == 0L || now - lastSecondSnapshotNs >= 1_000_000_000L)) {
            lastSecondSnapshotNs = now;
            logServerSecondSnapshot(ms);
        }
    }

    private void logServerSecondSnapshot(long tickMs) {
        MinecraftServer current = server;
        if (current == null) return;

        int worldCount = 0;
        for (ServerLevel ignored : current.getAllLevels()) worldCount++;

        DiagnosticsLog.log("SERVER_HEARTBEAT tick=" + tickCount + " tick_ms=" + tickMs
                + " worlds=" + worldCount + " players=" + current.getPlayerList().getPlayerCount()
                + " " + DiagnosticsLog.runtimeStats());

        serverEntityTracker.begin("server", System.nanoTime(), "tick=" + tickCount + " worlds=" + worldCount);
        for (ServerLevel level : current.getAllLevels()) {
            long count = 0L;
            String dimension = level.dimension().location().toString();
            for (Entity entity : level.getAllEntities()) {
                count++;
                serverEntityTracker.record("server", entity);
            }
            DiagnosticsLog.log("WORLD_SNAPSHOT side=server dimension=" + dimension + " entities=" + count
                    + " game_time=" + level.getGameTime() + " day_time=" + level.getDayTime());
        }
        serverEntityTracker.end("server");
        DiagnosticsLog.checkMemory();

        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            DiagnosticsLog.log("PLAYER_SNAPSHOT " + playerDescription(player)
                    + " health=" + fmt(player.getHealth())
                    + " food=" + player.getFoodData().getFoodLevel()
                    + " saturation=" + fmt(player.getFoodData().getSaturationLevel())
                    + " air=" + player.getAirSupply()
                    + " xp=" + player.totalExperience
                    + " selected_slot=" + player.getInventory().selected
                    + " selected_item=" + itemDescription(player.getMainHandItem())
                    + " inventory=" + playerInventoryDescription(player));
        }
    }

    // ---------------------------------------------------------------- entities / combat

    @SubscribeEvent
    public void entityJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        DiagnosticsLog.log("ENTITY_JOIN side=" + (event.getLevel().isClientSide() ? "client" : "server")
                + " dimension=" + event.getLevel().dimension().location() + " " + entityDescription(e));
    }

    @SubscribeEvent
    public void entityLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        DiagnosticsLog.log("ENTITY_LEAVE side=" + (event.getLevel().isClientSide() ? "client" : "server")
                + " dimension=" + event.getLevel().dimension().location() + " " + entityDescription(e));
    }

    @SubscribeEvent
    public void projectileImpact(ProjectileImpactEvent event) {
        DiagnosticsLog.log("PROJECTILE_IMPACT projectile=" + entityDescription(event.getProjectile())
                + " hit=" + event.getRayTraceResult().getType());
    }

    @SubscribeEvent
    public void attack(LivingAttackEvent event) {
        DiagnosticsLog.log("LIVING_ATTACK target=" + entityDescription(event.getEntity())
                + " source=" + damageSource(event.getSource()) + " amount=" + fmt(event.getAmount()));
    }

    @SubscribeEvent
    public void damage(LivingDamageEvent event) {
        DiagnosticsLog.log("LIVING_DAMAGE target=" + entityDescription(event.getEntity())
                + " source=" + damageSource(event.getSource()) + " amount=" + fmt(event.getAmount())
                + " health_after_event=" + fmt(event.getEntity().getHealth()));
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        DiagnosticsLog.log("LIVING_DEATH entity=" + entityDescription(event.getEntity())
                + " source=" + damageSource(event.getSource()));
    }

    // ---------------------------------------------------------------- items

    @SubscribeEvent
    public void itemFinished(LivingEntityUseItemEvent.Finish event) {
        ItemStack stack = event.getItem();
        DiagnosticsLog.log("ITEM_USE_FINISH user=" + entityDescription(event.getEntity())
                + " item=" + itemDescription(stack) + " remaining_count=" + stack.getCount());
    }

    @SubscribeEvent
    public void itemStart(LivingEntityUseItemEvent.Start event) {
        DiagnosticsLog.log("ITEM_USE_START user=" + entityDescription(event.getEntity())
                + " item=" + itemDescription(event.getItem()) + " duration=" + event.getDuration());
    }

    @SubscribeEvent
    public void itemStop(LivingEntityUseItemEvent.Stop event) {
        DiagnosticsLog.log("ITEM_USE_STOP user=" + entityDescription(event.getEntity())
                + " item=" + itemDescription(event.getItem()) + " duration=" + event.getDuration());
    }

    // ---------------------------------------------------------------- players / blocks / chunks

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        DiagnosticsLog.log("PLAYER_LOGIN " + playerDescription(event.getEntity()));
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        DiagnosticsLog.log("PLAYER_LOGOUT " + playerDescription(event.getEntity()));
    }

    @SubscribeEvent
    public void breakBlock(BlockEvent.BreakEvent event) {
        DiagnosticsLog.log("BLOCK_BREAK player=" + playerDescription(event.getPlayer())
                + " pos=" + event.getPos().toShortString() + " block=" + blockDescription(event.getState())
                + " dimension=" + levelDimension(event.getLevel()));
    }

    @SubscribeEvent
    public void placeBlock(BlockEvent.EntityPlaceEvent event) {
        DiagnosticsLog.log("BLOCK_PLACE actor=" + (event.getEntity() == null ? "unknown" : entityDescription(event.getEntity()))
                + " pos=" + event.getPos().toShortString() + " block=" + blockDescription(event.getPlacedBlock())
                + " dimension=" + levelDimension(event.getLevel()));
    }

    @SubscribeEvent
    public void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        DiagnosticsLog.log("PLAYER_RIGHT_CLICK_BLOCK player=" + playerDescription(event.getEntity())
                + " pos=" + event.getPos().toShortString()
                + " block=" + blockDescription(event.getLevel().getBlockState(event.getPos()))
                + " item=" + itemDescription(event.getItemStack()));
    }

    @SubscribeEvent
    public void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        DiagnosticsLog.log("PLAYER_LEFT_CLICK_BLOCK player=" + playerDescription(event.getEntity())
                + " pos=" + event.getPos().toShortString()
                + " block=" + blockDescription(event.getLevel().getBlockState(event.getPos())));
    }

    @SubscribeEvent
    public void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        DiagnosticsLog.log("PLAYER_RIGHT_CLICK_ITEM player=" + playerDescription(event.getEntity())
                + " item=" + itemDescription(event.getItemStack()));
    }

    @SubscribeEvent
    public void chunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        DiagnosticsLog.log("CHUNK_LOAD dimension=" + levelDimension(event.getLevel())
                + " chunk_x=" + event.getChunk().getPos().x + " chunk_z=" + event.getChunk().getPos().z);
    }

    // ---------------------------------------------------------------- helpers

    private void logLoadedMods() {
        DiagnosticsLog.log("MOD_LIST_BEGIN count=" + ModList.get().getMods().size());
        ModList.get().getMods().stream()
                .sorted(Comparator.comparing((IModInfo m) -> m.getModId()))
                .forEach(m -> DiagnosticsLog.log("MOD_LOADED id=" + m.getModId()
                        + " name=" + DiagnosticsLog.clean(m.getDisplayName())
                        + " version=" + DiagnosticsLog.clean(m.getVersion().toString())));
        DiagnosticsLog.log("MOD_LIST_END");
    }

    private void logServerSnapshot(String reason) {
        if (server == null) return;
        DiagnosticsLog.log("INCIDENT_SNAPSHOT reason=" + reason + " tick=" + tickCount
                + " last_tick_ms=" + lastTickDurationMs
                + " players=" + server.getPlayerList().getPlayerCount()
                + " worlds=" + serverWorldCount());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            DiagnosticsLog.log("INCIDENT_PLAYER " + playerDescription(p));
        }
    }

    private int serverWorldCount() {
        int count = 0;
        MinecraftServer current = server;
        if (current != null) {
            for (ServerLevel ignored : current.getAllLevels()) count++;
        }
        return count;
    }

    static String entityDescription(Entity e) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        String namespace = key == null ? "unknown" : key.getNamespace();
        return "name=" + DiagnosticsLog.clean(e.getName().getString())
                + " type=" + (key == null ? "unknown" : key)
                + " mod_id_guess=" + namespace
                + " class=" + e.getClass().getName()
                + " id=" + e.getId() + " uuid=" + e.getUUID()
                + " pos=" + fmt(e.getX()) + "," + fmt(e.getY()) + "," + fmt(e.getZ())
                + " motion=" + fmt(e.getDeltaMovement().x) + "," + fmt(e.getDeltaMovement().y) + "," + fmt(e.getDeltaMovement().z)
                + " alive=" + e.isAlive() + " tick_count=" + e.tickCount
                + " on_ground=" + e.onGround()
                + " passenger=" + e.isPassenger()
                + " vehicle=" + (e.getVehicle() == null ? "none" : e.getVehicle().getId())
                + (e instanceof LivingEntity living
                        ? " health=" + fmt(living.getHealth()) + " max_health=" + fmt(living.getMaxHealth())
                        : "")
                + (e instanceof ItemEntity item
                        ? " item=" + itemDescription(item.getItem())
                        : "");
    }

    static String entityTypeId(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "unknown" : key.toString();
    }

    /** Compact fingerprint of everything that matters for "did this entity change since last scan". */
    static String entityChangeSignature(Entity e) {
        StringBuilder state = new StringBuilder(256);
        state.append(entityTypeId(e)).append('|')
                .append(e.getClass().getName()).append('|')
                .append(e.getName().getString()).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getX()))).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getY()))).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getZ()))).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getDeltaMovement().x))).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getDeltaMovement().y))).append('|')
                .append(Long.toHexString(Double.doubleToRawLongBits(e.getDeltaMovement().z))).append('|')
                .append(e.isAlive()).append('|')
                .append(e.onGround()).append('|')
                .append(e.isPassenger()).append('|')
                .append(e.getVehicle() == null ? "none" : e.getVehicle().getUUID());
        if (e instanceof LivingEntity living) {
            state.append('|').append(Integer.toHexString(Float.floatToRawIntBits(living.getHealth())))
                    .append('|').append(Integer.toHexString(Float.floatToRawIntBits(living.getMaxHealth())));
        }
        if (e instanceof ItemEntity item) {
            state.append('|').append(itemDescription(item.getItem()));
        }
        return state.toString();
    }

    static String playerDescription(Player p) {
        return "player=" + DiagnosticsLog.clean(p.getGameProfile().getName())
                + " uuid=" + p.getUUID() + " dimension=" + p.level().dimension().location()
                + " x=" + fmt(p.getX()) + " y=" + fmt(p.getY()) + " z=" + fmt(p.getZ())
                + " yaw=" + fmt(p.getYRot()) + " pitch=" + fmt(p.getXRot())
                + " sprint=" + p.isSprinting() + " crouch=" + p.isCrouching()
                + " on_ground=" + p.onGround() + " velocity=" + fmt(p.getDeltaMovement().x)
                + "," + fmt(p.getDeltaMovement().y) + "," + fmt(p.getDeltaMovement().z);
    }

    private static String itemDescription(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "id=" + key + " count=" + stack.getCount()
                + " display=" + DiagnosticsLog.clean(stack.getHoverName().getString())
                + (stack.getTag() == null ? "" : " nbt=" + stack.getTag());
    }

    static String playerInventoryDescription(Player player) {
        StringBuilder inventory = new StringBuilder();
        Inventory slots = player.getInventory();
        for (int slot = 0; slot < slots.getContainerSize(); slot++) {
            ItemStack stack = slots.getItem(slot);
            if (stack.isEmpty()) continue;
            if (inventory.length() > 0) inventory.append(';');
            inventory.append(slot).append('=').append(itemDescription(stack));
        }
        return inventory.length() == 0 ? "empty" : inventory.toString();
    }

    private static String blockDescription(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "id=" + key + " properties=" + DiagnosticsLog.clean(state.toString());
    }

    private static String damageSource(DamageSource source) {
        String direct = source.getDirectEntity() == null ? "none" : entityDescription(source.getDirectEntity());
        String causing = source.getEntity() == null ? "none" : entityDescription(source.getEntity());
        return "type=" + source.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("unknown")
                + " direct=" + direct + " causing=" + causing;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String levelDimension(LevelAccessor accessor) {
        return accessor instanceof Level level ? level.dimension().location().toString() : "unknown";
    }
}
