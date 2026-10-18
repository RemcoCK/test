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
    private int scale = 4;
    private float damage = 1.0f; // 1 HP = half a heart in vanilla MC
    private int duration = 12;
    private final Path settingsPath = FabricLoader.getInstance().getConfigDir().resolve("nerd-horror.properties");

    private record Pending(long due, String command) {}
    private record Spawned(long removeAt, String tag, String dimension) {}

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
                        .executes(ctx -> trigger(ctx, monster))));
            }
            for (String effect : EFFECTS) {
                root.then(Commands.literal(effect)
                    .then(Commands.argument("speler", StringArgumentType.word())
                        .executes(ctx -> trigger(ctx, effect))));
            }
            root.then(Commands.literal("config")
                .then(Commands.literal("size")
                    .then(Commands.argument("waarde", IntegerArgumentType.integer(1, 8))
                        .executes(ctx -> configSize(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "waarde")))))
                .then(Commands.literal("damage")
                    .then(Commands.argument("waarde", FloatArgumentType.floatArg(0.0f, 4.0f))
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
        LOGGER.info("Nerd Horror Control 1.0.0 loaded (server-side, /horror)");
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
        removeAll(source.getServer());
        int removed = spawned.size();
        spawned.clear();
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
        String summary = "Horror " + event + " gestart bij " + targets.size() + " speler(s).";
        ctx.getSource().sendSuccess(() -> Component.literal(summary), true);
        return targets.size();
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
                command(server, "title " + name + " times 0 35 10");
                command(server, "title " + name + " title {text:\"KIJK ACHTER JE!\",color:\"dark_red\",bold:true}");
                sound(server, at, "minecraft:entity.warden.roar", "0.8");
                command(server, at + "effect give @s minecraft:darkness 3 0 true");
                particle(server, at, "minecraft:smoke", 35);
            }
            case "darkness" -> {
                command(server, at + "effect give @s minecraft:darkness 8 0 true");
                sound(server, at, "minecraft:ambient.cave", "1");
            }
            case "thunder" -> {
                sound(server, at, "minecraft:entity.lightning_bolt.thunder", "1");
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
        String name = player.getName().getString();
        String id = "nh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String dimension = player.level().dimension().identifier().toString();
        String nbt = "{Tags:[\"" + ROOT_TAG + "\",\"" + id + "\"]"
            + ((type.equals("creeper") || type.equals("skeleton")) ? ",NoAI:1b,Silent:1b,Invulnerable:1b" : "")
            + "}";
        // Spawn ahead of the player. No custom entity type, texture, or resource pack.
        command(server, "execute as " + name + " at @s positioned ^ ^ ^4 run summon minecraft:" + type + " ~ ~ ~ " + nbt);
        String selector = "@e[tag=" + id + ",limit=1,sort=nearest]";
        command(server, at + "attribute " + selector + " minecraft:scale base set " + scale);
        if (type.equals("spider") || type.equals("zombie")) {
            command(server, at + "attribute " + selector + " minecraft:attack_damage base set " + damage);
        }
        sound(server, at, type.equals("spider") ? "minecraft:entity.spider.ambient" : "minecraft:entity.warden.roar", "0.8");
        spawned.add(new Spawned(ticks + duration * 20L, id, dimension));
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
            Spawned mob = mobs.next();
            if (mob.removeAt() <= ticks) {
                killTag(server, mob.dimension(), mob.tag());
                mobs.remove();
            }
        }
    }

    private void removeAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().identifier().toString();
            killTag(server, dimension, ROOT_TAG);
        }
    }

    private void killTag(MinecraftServer server, String dimension, String tag) {
        command(server, "execute in " + dimension + " run kill @e[tag=" + tag + "]");
    }

    private void command(MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (RuntimeException ex) {
            LOGGER.error("Horror-action command failed: {}", command, ex);
        }
    }

    private void loadSettings() {
        if (!Files.exists(settingsPath)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(settingsPath)) {
            p.load(in);
            scale = Math.clamp(Integer.parseInt(p.getProperty("size", "4")), 1, 8);
            damage = Math.clamp(Float.parseFloat(p.getProperty("damage", "1")), 0.0f, 4.0f);
            duration = Math.clamp(Integer.parseInt(p.getProperty("duration", "12")), 3, 60);
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
