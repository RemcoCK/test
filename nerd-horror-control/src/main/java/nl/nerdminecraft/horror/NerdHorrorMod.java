package nl.nerdminecraft.horror;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Server-only Fabric 26.3 horror prank commands. Uses only vanilla packets,
 * sounds, titles, attributes and entity types: clients need no extra mods.
 */
public final class NerdHorrorMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("nerdhorror");
    private static final String ROOT_TAG = "nerdhorror";
    private static final String[] MONSTERS = { "spider", "zombie", "skeleton", "creeper" };
    private static final String[] EFFECTS = {
        "jumpscare", "darkness", "thunder", "heartbeat", "whisper", "footsteps",
        "fakecreeper", "cavesounds", "blindness", "eyes"
    };

    private final List<Pending> pending = new ArrayList<>();
    private final List<Spawned> spawned = new ArrayList<>();
    private long ticks = 0;
    private int scale = 7;
    private float damage = 1.0f; // 1 HP = half a heart in vanilla MC
    private int duration = 18;
    private final Path settingsPath = FabricLoader.getInstance().getConfigDir().resolve("nerd-horror.properties");

    private record Pending(long due, String command) {}
    // Direct entity references let us remove prank mobs WITHOUT /kill, drops or XP.
    private record Spawned(long removeAt, Mob mob, UUID target, String type) {}

    @Override
    public void onInitialize() {
        loadSettings();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("horror")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())));

            for (String monster : MONSTERS) {
                root.then(Commands.literal(monster)
                    .then(Commands.argument("speler", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestPlayers(ctx, builder))
                        .executes(ctx -> trigger(ctx, monster))));
            }
            for (String effect : EFFECTS) {
                root.then(Commands.literal(effect)
                    .then(Commands.argument("speler", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestPlayers(ctx, builder))
                        .executes(ctx -> trigger(ctx, effect))));
            }
            root.then(Commands.literal("config")
                .then(Commands.literal("size")
                    .then(Commands.argument("waarde", IntegerArgumentType.integer(1, 12))
                        .executes(ctx -> configSize(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "waarde")))))
                .then(Commands.literal("damage")
                    .then(Commands.argument("waarde", FloatArgumentType.floatArg(0.0f, 6.0f))
                        .executes(ctx -> configDamage(ctx.getSource(), FloatArgumentType.getFloat(ctx, "waarde")))))
                .then(Commands.literal("duration")
                    .then(Commands.argument("seconden", IntegerArgumentType.integer(3, 60))
                        .executes(ctx -> configDuration(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconden"))))));
            dispatcher.register(root);
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(this::removeAll);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            pending.clear();
            // Remove spooky monsters before chunks are saved on shutdown.
            removeAll(server);
        });
        LOGGER.info("Nerd Horror Control 1.1.0 loaded (server-side, /horror)");
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
            "Nerd Horror: /horror <spider|zombie|skeleton|creeper|jumpscare|darkness|thunder|heartbeat|whisper|footsteps|fakecreeper|cavesounds|blindness|eyes> <speler|all>; /horror config <size|damage|duration> <waarde>; /horror stop; /horror status"
        ), false);
        return 1;
    }

    private int status(CommandSourceStack source) {
        String message = "Nerd Horror | Grootte: " + scale + "x | Schade: " + damage
            + " HP per slag | Duur: " + duration + "s | Actieve monsters: " + spawned.size();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private int configSize(CommandSourceStack source, int size) {
        scale = size;
        saveSettings();
        source.sendSuccess(() -> Component.literal("Horror-monsters zijn nu " + size + "x zo groot."), false);
        return 1;
    }

    private int configDamage(CommandSourceStack source, float amount) {
        damage = amount;
        saveSettings();
        source.sendSuccess(() -> Component.literal("Horror-monster melee-schade: " + amount + " HP per slag."), false);
        return 1;
    }

    private int configDuration(CommandSourceStack source, int seconds) {
        duration = seconds;
        saveSettings();
        source.sendSuccess(() -> Component.literal("Horror-monsters verdwijnen na " + seconds + " seconden."), false);
        return 1;
    }

    private int stop(CommandSourceStack source) {
        pending.clear();
        int removed = spawned.size();
        removeAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Alle lopende horroracties gestopt (" + removed + " monsters)."), false);
        return 1;
    }

    private int trigger(CommandContext<CommandSourceStack> ctx, String event) {
        String target = StringArgumentType.getString(ctx, "speler");
        MinecraftServer server = ctx.getSource().getServer();
        List<ServerPlayer> targets = new ArrayList<>();
        if (target.equalsIgnoreCase("all")) {
            targets.addAll(server.getPlayerList().getPlayers());
        } else {
            ServerPlayer player = server.getPlayerList().getPlayerByName(target);
            if (player != null) targets.add(player);
        }
        if (targets.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Geen online speler gevonden: " + target));
            return 0;
        }
        // Limit server load from accidental repeated "all" monster calls.
        if (isMonster(event) && targets.size() + spawned.size() > 40) {
            ctx.getSource().sendFailure(Component.literal("Te veel actieve horror-monsters (maximum 40). Gebruik /horror stop."));
            return 0;
        }
        for (ServerPlayer player : targets) {
            perform(event, player, server);
        }
        // Silent action: no success message to operator or broadcast to other OPs.
        return targets.size();
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
        CommandContext<CommandSourceStack> ctx,
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        builder.suggest("all");
        for (String name : ctx.getSource().getOnlinePlayerNames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private boolean isMonster(String event) {
        for (String candidate : MONSTERS) {
            if (candidate.equals(event)) return true;
        }
        return false;
    }

    private void perform(String event, ServerPlayer player, MinecraftServer server) {
        String name = player.getName().getString();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return;
        String at = "execute as " + name + " at @s run ";
        switch (event) {
            case "spider", "zombie", "skeleton", "creeper" -> spawnMonster(server, player, event, at);
            case "jumpscare" -> {
                // Two-stage scare: silence/darkness then a loud flash in the player's face.
                command(server, "title " + name + " times 0 28 5");
                command(server, at + "effect give @s minecraft:darkness 4 0 true");
                command(server, "title " + name + " title {text:\"??????????\",color:\"dark_red\",obfuscated:true,bold:true}");
                sound(server, at, "minecraft:entity.warden.heartbeat", "0.6");
                later(16, "title " + name + " title {text:\"ACHTER JE!\",color:\"red\",bold:true}");
                later(16, at + "playsound minecraft:entity.warden.sonic_boom master @s ~ ~ ~ 1.5 1.2");
                later(16, at + "playsound minecraft:entity.enderman.scream master @s ~ ~ ~ 1.4 0.6");
                later(16, at + "particle minecraft:sonic_boom ^ ^1 ^1 0 0 0 0 1 force @s");
                later(29, "title " + name + " subtitle {text:\"Hij staat achter je...\",color:\"dark_gray\",italic:true}");
            }
            case "darkness" -> {
                command(server, at + "effect give @s minecraft:darkness 8 0 true");
                sound(server, at, "minecraft:ambient.cave", "1");
            }
            case "thunder" -> {
                // Real visual lightning entity, but no block fire or player damage.
                LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, player.level());
                bolt.setVisualOnly(true);
                bolt.setPos(player.getX() + 3.0, player.getY(), player.getZ() + 2.0);
                player.level().addFreshEntity(bolt);
                particle(server, at, "minecraft:electric_spark", 45);
            }
            case "heartbeat" -> {
                for (int delay = 0; delay < 100; delay += 22) {
                    later(delay, at + "playsound minecraft:entity.warden.heartbeat master @s ~ ~ ~ 1 0.8");
                }
            }
            case "whisper" -> {
                player.sendSystemMessage(Component.literal("§8[???] §7Ik kan je zien..."));
                later(35, "tellraw " + name + " {text:\"Draai je maar niet om.\",color:\"dark_gray\"}");
                sound(server, at, "minecraft:entity.enderman.stare", "0.5");
            }
            case "footsteps" -> {
                for (int delay = 0; delay < 100; delay += 14) {
                    later(delay, at + "playsound minecraft:block.stone.step master @s ~2 ~ ~2 1 0.7");
                }
            }
            case "fakecreeper" -> {
                sound(server, at, "minecraft:entity.creeper.primed", "1");
                later(40, at + "playsound minecraft:entity.generic.explode master @s ~ ~ ~ 1 1");
                later(40, at + "particle minecraft:explosion_emitter ~ ~1 ~ 0 0 0 0 1 force @s");
            }
            case "cavesounds" -> {
                for (int delay = 0; delay < 100; delay += 35) {
                    later(delay, at + "playsound minecraft:ambient.cave master @s ~ ~ ~ 1 0.5");
                }
            }
            case "blindness" -> {
                command(server, at + "effect give @s minecraft:blindness 5 0 true");
                sound(server, at, "minecraft:entity.enderman.scream", "0.7");
            }
            case "eyes" -> {
                particle(server, at, "minecraft:soul_fire_flame", 40);
                sound(server, at, "minecraft:entity.enderman.stare", "0.6");
            }
            default -> LOGGER.warn("Unknown horror action {}", event);
        }
    }

    private void spawnMonster(MinecraftServer server, ServerPlayer player, String type, String at) {
        String id = "nh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ServerLevel level = player.level();
        // Spawn 12-16 blocks away rather than directly inside/next to the target.
        // Choose a patch of solid ground to avoid a mob appearing inside a hill.
        double yaw = Math.toRadians(player.getYRot());
        double distance = type.equals("spider") ? 16.0 : 13.0;
        double x = player.getX() + Math.sin(yaw) * distance;
        double z = player.getZ() - Math.cos(yaw) * distance;
        double y = findGround(level, x, player.getY(), z);
        String nbt = "{Tags:[\"" + ROOT_TAG + "\",\"" + id + "\"]"
            + ",DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CanPickUpLoot:0b"
            + (type.equals("creeper") ? ",NoAI:1b,Silent:1b,Invulnerable:1b" : "")
            + (type.equals("skeleton") ? ",Invulnerable:1b,Fire:-1s" : "")
            + "}";
        // Use the actual target dimension, not the overworld command source.
        String pos = String.format(Locale.ROOT, "%.2f %.2f %.2f", x, y, z);
        command(server, "execute in " + level.dimension().identifier() + " run summon minecraft:" + type + " " + pos + " " + nbt);
        Mob mob = level.getEntitiesOfClass(Mob.class,
            new AABB(x - 4, y - 3, z - 4, x + 4, y + 8, z + 4),
            candidate -> candidate.entityTags().contains(id)).stream().findFirst().orElse(null);
        if (mob == null) {
            LOGGER.warn("Horror mob did not spawn: {} for {}", type, player.getName().getString());
            return;
        }
        // Scaling the actual vanilla model is visible without client-side mods.
        command(server, "execute in " + level.dimension().identifier()
            + " run attribute @e[tag=" + id + ",limit=1] minecraft:scale base set " + scale);
        if (type.equals("spider") || type.equals("zombie")) {
            command(server, "execute in " + level.dimension().identifier()
                + " run attribute @e[tag=" + id + ",limit=1] minecraft:attack_damage base set " + damage);
        }
        if (type.equals("skeleton")) {
            // Fire resistance + invulnerability protects against sunlight.
            command(server, "execute in " + level.dimension().identifier()
                + " run effect give @e[tag=" + id + ",limit=1] minecraft:fire_resistance 120 0 true");
        }
        if (!type.equals("creeper")) {
            mob.setTarget(player);
            mob.getNavigation().moveTo(player, type.equals("spider") ? 1.85 : 1.45);
        }
        sound(server, at, type.equals("spider") ? "minecraft:entity.spider.ambient" : "minecraft:entity.warden.roar", "0.65");
        spawned.add(new Spawned(ticks + duration * 20L, mob, player.getUUID(), type));
    }

    private double findGround(ServerLevel level, double x, double playerY, double z) {
        // Search close to the player's elevation; not designed for enclosed caves.
        for (int offset = 4; offset >= -12; offset--) {
            BlockPos pos = BlockPos.containing(x, playerY + offset, z);
            if (level.getBlockState(pos.below()).isSolid()
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()) {
                return pos.getY();
            }
        }
        return playerY;
    }

    private void sound(MinecraftServer server, String at, String sound, String pitch) {
        command(server, at + "playsound " + sound + " master @s ~ ~ ~ 1 " + pitch);
    }

    private void particle(MinecraftServer server, String at, String particle, int count) {
        command(server, at + "particle " + particle + " ~ ~1 ~ 0.9 0.8 0.9 0.05 " + count + " force @s");
    }

    private void later(long delayTicks, String cmd) {
        pending.add(new Pending(ticks + delayTicks, cmd));
    }

    private void tick(MinecraftServer server) {
        ticks++;
        Iterator<Pending> queue = pending.iterator();
        while (queue.hasNext()) {
            Pending item = queue.next();
            if (item.due() <= ticks) {
                queue.remove();
                command(server, item.command());
            }
        }
        Iterator<Spawned> mobs = spawned.iterator();
        while (mobs.hasNext()) {
            Spawned entry = mobs.next();
            Mob mob = entry.mob();
            if (mob.isRemoved()) {
                mobs.remove();
                continue;
            }
            if (entry.removeAt() <= ticks) {
                mob.discard();  // /kill would create loot, XP and chat spam.
                mobs.remove();
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(entry.target());
            if (target == null || !target.isAlive() || target.level() != mob.level()) {
                mob.discard();
                mobs.remove();
                continue;
            }
            if (entry.type().equals("creeper")) {
                // A controlled, non-exploding giant creeper that rushes the player.
                // NoAI means it can NEVER explode and damage builds/claims.
                if (ticks % 2 == 0) {
                    double dx = target.getX() - mob.getX();
                    double dz = target.getZ() - mob.getZ();
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 3.0) {
                        double speed = 0.52;
                        mob.setPos(mob.getX() + dx / len * speed, mob.getY(), mob.getZ() + dz / len * speed);
                        mob.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    } else {
                        String name = target.getName().getString();
                        String at = "execute as " + name + " at @s run ";
                        sound(server, at, "minecraft:entity.creeper.primed", "0.65");
                        later(12, at + "playsound minecraft:entity.generic.explode master @s ~ ~ ~ 1 1");
                        later(12, at + "particle minecraft:explosion_emitter ~ ~1 ~ 0 0 0 0 1 force @s");
                        mob.discard();
                        mobs.remove();
                    }
                }
            } else if (ticks % 20 == 0) {
                // Vanilla AI may pick another mob/player: keep pursuing the named target.
                mob.setTarget(target);
                mob.getNavigation().moveTo(target, entry.type().equals("spider") ? 1.85 : 1.45);
            }
        }
    }

    private void removeAll(MinecraftServer server) {
        for (Spawned entry : spawned) {
            if (!entry.mob().isRemoved()) entry.mob().discard();
        }
        spawned.clear();
    }

    private void command(MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
        } catch (RuntimeException ex) {
            LOGGER.error("Horror-action command failed: {}", command, ex);
        }
    }

    private void loadSettings() {
        if (!Files.exists(settingsPath)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(settingsPath)) {
            p.load(in);
            scale = Math.clamp(Integer.parseInt(p.getProperty("size", "7")), 1, 12);
            damage = Math.clamp(Float.parseFloat(p.getProperty("damage", "1")), 0.0f, 6.0f);
            duration = Math.clamp(Integer.parseInt(p.getProperty("duration", "18")), 3, 60);
        } catch (IOException | NumberFormatException ex) {
            LOGGER.warn("Could not load nerd-horror.properties; using safe defaults", ex);
        }
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("size", Integer.toString(scale));
        p.setProperty("damage", Float.toString(damage));
        p.setProperty("duration", Integer.toString(duration));
        try {
            Files.createDirectories(settingsPath.getParent());
            try (OutputStream out = Files.newOutputStream(settingsPath)) {
                p.store(out, "Nerd Horror Control server-side settings");
            }
        } catch (IOException ex) {
            LOGGER.error("Could not save nerd-horror.properties", ex);
        }
    }
}
