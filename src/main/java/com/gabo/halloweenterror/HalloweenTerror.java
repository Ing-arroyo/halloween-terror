package com.gabo.halloweenterror;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HalloweenTerror - terror psicológico para tu servidor (NeoForge 1.21.1, solo servidor).
 *
 * Comandos (OP nivel 2):
 *   /terror boss   -> invoca un jefe ahora mismo (para probar)
 *   /terror sign   -> coloca un cartel ahora mismo
 *   /terror on|off -> activa o desactiva el sistema
 */
@Mod("halloweenterror")
@EventBusSubscriber(modid = "halloweenterror")
public class HalloweenTerror {

    // ===================== AJUSTES (en segundos) =====================
    static final int SOUND_MIN = 15,   SOUND_MAX = 45;    // sonidos ambientales
    static final int SIGN_MIN = 90,    SIGN_MAX = 240;    // carteles
    static final int WHISPER_MIN = 120, WHISPER_MAX = 300; // mensajes en la barra de acción
    static final int DARK_MIN = 180,   DARK_MAX = 420;    // pulsos de oscuridad
    static final int BOSS_MIN = 480,   BOSS_MAX = 900;    // aparición de jefes (8-15 min)
    static final int SIGN_LIFETIME = 45;                  // cuánto dura un cartel
    static final int BOSS_LIFETIME = 240;                 // cuánto dura un jefe si nadie lo mata
    static final float BOSS_HEALTH_MULT = 3.0f;           // multiplicador de vida del jefe
    // =================================================================

    private static boolean enabled = true;
    private static long ticks = 0;
    private static int bossTimer = -1;
    private static final Map<UUID, int[]> TIMERS = new HashMap<>();
    private static final List<PendingSign> SIGNS = new ArrayList<>();
    private static final List<Boss> BOSSES = new ArrayList<>();
    private static List<SoundEvent> sounds;
    private static List<EntityType<?>> mobTypes;

    private record PendingSign(ServerLevel level, BlockPos pos, long expireTick) {}

    private static class Boss {
        Mob mob; ServerBossEvent bar; ServerPlayer target; long expireTick;
    }

    static final String[][] SIGN_MESSAGES = {
        {"", "NO MIRES", "ATRAS", ""},
        {"", "ELLOS YA", "SABEN DONDE", "DUERMES"},
        {"", "NO ESTAS", "SOLO", ""},
        {"TE ESTAN", "OBSERVANDO", "DESDE LA", "OSCURIDAD"},
        {"", "NO DEBISTE", "VENIR", ""},
        {"", "CORRE", "", ""},
        {"NO HAY", "VUELTA", "ATRAS", ""},
        {"ALGUIEN", "CAMINA", "DETRAS", "DE TI"},
        {"", "ESTO NO ES", "UN JUEGO", ""},
        {"YA LO", "OYES", "VERDAD?", ""},
        {"", "NO ERES", "EL PRIMERO", ""},
        {"ESTUVE", "AQUI", "TODO EL", "TIEMPO"},
    };

    static final String[] WHISPERS = {
        "Algo respira detrás de ti...",
        "Esa sombra no estaba ahí antes.",
        "Escuchaste pasos. No eran tuyos.",
        "No estás solo.",
        "Deberías volver. Ahora.",
        "Alguien susurró tu nombre.",
        "El silencio es demasiado profundo.",
        "Te está mirando. Ahora mismo.",
        "No te des la vuelta.",
    };

    static final String[] BOSS_TITLES = {
        "El Que Observa", "Lo Que Vive en la Niebla", "Heraldo del Vacío",
        "El Hambriento", "La Sombra Sin Nombre", "Ánima Perdida",
        "El Visitante de Medianoche", "Cosecha de Almas",
    };

    public HalloweenTerror() {}

    // ----------------------------------------------------------------- tick
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (!enabled) return;
        MinecraftServer server = e.getServer();
        ticks++;
        tickSigns();
        tickBosses();

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer p : players) {
            if (p.isSpectator()) continue;
            RandomSource r = p.getRandom();
            int[] t = TIMERS.computeIfAbsent(p.getUUID(), k -> new int[]{
                rnd(r, SOUND_MIN, SOUND_MAX), rnd(r, SIGN_MIN, SIGN_MAX),
                rnd(r, WHISPER_MIN, WHISPER_MAX), rnd(r, DARK_MIN, DARK_MAX)});

            if (--t[0] <= 0) { t[0] = rnd(r, SOUND_MIN, SOUND_MAX); ambientSound(p); }
            if (--t[1] <= 0) { t[1] = rnd(r, SIGN_MIN, SIGN_MAX); placeSign(p); }
            if (--t[2] <= 0) { t[2] = rnd(r, WHISPER_MIN, WHISPER_MAX); whisper(p); }
            if (--t[3] <= 0) { t[3] = rnd(r, DARK_MIN, DARK_MAX); darkness(p); }
        }

        if (!players.isEmpty()) {
            if (bossTimer < 0) bossTimer = rnd(players.get(0).getRandom(), BOSS_MIN, BOSS_MAX);
            if (--bossTimer <= 0) {
                RandomSource r = players.get(0).getRandom();
                bossTimer = rnd(r, BOSS_MIN, BOSS_MAX);
                ServerPlayer victim = players.get(r.nextInt(players.size()));
                if (!victim.isSpectator()) spawnBoss(victim);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        SIGNS.clear();
        BOSSES.clear();
        TIMERS.clear();
        bossTimer = -1;
    }

    private static int rnd(RandomSource r, int minSec, int maxSec) {
        return (minSec + r.nextInt(maxSec - minSec + 1)) * 20;
    }

    // --------------------------------------------------------------- sonidos
    private static List<SoundEvent> ambient() {
        if (sounds == null) {
            sounds = List.of(
                SoundEvents.AMBIENT_CAVE.value(),
                SoundEvents.WARDEN_HEARTBEAT,
                SoundEvents.ENDERMAN_STARE,
                SoundEvents.ENDERMAN_SCREAM,
                SoundEvents.GHAST_AMBIENT,
                SoundEvents.PHANTOM_AMBIENT,
                SoundEvents.VEX_AMBIENT,
                SoundEvents.ELDER_GUARDIAN_AMBIENT,
                SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR,
                SoundEvents.STONE_STEP,
                SoundEvents.GRAVEL_STEP
            );
        }
        return sounds;
    }

    private static void playAt(ServerPlayer p, SoundEvent s, double x, double y, double z, float vol, float pitch) {
        p.connection.send(new ClientboundSoundPacket(
            Holder.direct(s), SoundSource.AMBIENT, x, y, z, vol, pitch, p.getRandom().nextLong()));
    }

    private static void ambientSound(ServerPlayer p) {
        RandomSource r = p.getRandom();
        List<SoundEvent> list = ambient();
        SoundEvent s = list.get(r.nextInt(list.size()));
        double ang = r.nextDouble() * Math.PI * 2;
        double d = 6 + r.nextInt(7);
        double x = p.getX() + Math.cos(ang) * d;
        double z = p.getZ() + Math.sin(ang) * d;
        float pitch = 0.6f + r.nextFloat() * 0.5f; // más grave = más inquietante
        playAt(p, s, x, p.getY(), z, 0.9f, pitch);

        // a veces, una pisada "doble" justo detrás de ti
        if (s == SoundEvents.STONE_STEP || s == SoundEvents.GRAVEL_STEP) {
            playAt(p, s, x, p.getY(), z, 0.9f, pitch * 0.95f);
        }
    }

    private static void whisper(ServerPlayer p) {
        String msg = WHISPERS[p.getRandom().nextInt(WHISPERS.length)];
        p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
        playAt(p, SoundEvents.WARDEN_NEARBY_CLOSE, p.getX(), p.getY(), p.getZ(), 0.6f, 0.7f);
    }

    private static void darkness(ServerPlayer p) {
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 160, 0, false, false, false));
        playAt(p, SoundEvents.WARDEN_HEARTBEAT, p.getX(), p.getY(), p.getZ(), 1.0f, 0.8f);
    }

    // --------------------------------------------------------------- carteles
    private static BlockPos findFloor(ServerLevel level, int x, int z, int cy) {
        for (int y = cy + 3; y >= cy - 5; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.below();
            if (level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                return pos;
            }
        }
        return null;
    }

    static void placeSign(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        RandomSource r = level.random;
        Vec3 look = p.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1.0E-4) dir = new Vec3(0, 0, 1);
        dir = dir.normalize();

        boolean behind = r.nextInt(3) == 0; // 1/3 de las veces aparece a tus espaldas
        for (int attempt = 0; attempt < 12; attempt++) {
            double d = 3 + r.nextInt(5);
            double side = (r.nextDouble() - 0.5) * 4;
            double sign = behind ? -1 : 1;
            int x = Mth.floor(p.getX() + dir.x * d * sign - dir.z * side);
            int z = Mth.floor(p.getZ() + dir.z * d * sign + dir.x * side);
            BlockPos pos = findFloor(level, x, z, p.getBlockY());
            if (pos == null) continue;

            float yaw = behind ? p.getYRot() : p.getYRot() + 180f;
            BlockState state = Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, Mth.floor(yaw * 16.0F / 360.0F + 0.5F) & 15);
            level.setBlock(pos, state, 3);

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SignBlockEntity sbe) {
                String[] lines = SIGN_MESSAGES[r.nextInt(SIGN_MESSAGES.length)];
                SignText text = new SignText();
                for (int i = 0; i < 4; i++) {
                    text = text.setMessage(i, Component.literal(lines[i]));
                }
                text = text.setColor(DyeColor.RED).setHasGlowingText(true);
                sbe.setText(text, true);
                sbe.setWaxed(true);
                sbe.setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
            }
            SIGNS.add(new PendingSign(level, pos, ticks + SIGN_LIFETIME * 20L));
            playAt(p, SoundEvents.WOOD_PLACE, pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0.8f, 0.5f);
            return;
        }
    }

    private static void tickSigns() {
        Iterator<PendingSign> it = SIGNS.iterator();
        while (it.hasNext()) {
            PendingSign s = it.next();
            if (ticks >= s.expireTick()) {
                if (s.level().getBlockState(s.pos()).is(Blocks.OAK_SIGN)) {
                    s.level().removeBlock(s.pos(), false);
                }
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ jefes
    private static List<EntityType<?>> mobTypes() {
        if (mobTypes == null) {
            mobTypes = new ArrayList<>();
            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                MobCategory c = t.getCategory();
                if (!t.canSummon() || t == EntityType.ENDER_DRAGON) continue;
                if (c == MobCategory.MISC || c == MobCategory.WATER_CREATURE
                    || c == MobCategory.WATER_AMBIENT || c == MobCategory.UNDERGROUND_WATER_CREATURE
                    || c == MobCategory.AXOLOTLS) continue;
                mobTypes.add(t);
            }
        }
        return mobTypes;
    }

    static boolean spawnBoss(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        RandomSource r = level.random;
        List<EntityType<?>> types = mobTypes();
        if (types.isEmpty()) return false;

        for (int attempt = 0; attempt < 30; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double d = 14 + r.nextInt(8);
            int x = Mth.floor(p.getX() + Math.cos(ang) * d);
            int z = Mth.floor(p.getZ() + Math.sin(ang) * d);
            BlockPos pos = findFloor(level, x, z, p.getBlockY());
            if (pos == null) continue;

            EntityType<?> type = types.get(r.nextInt(types.size()));
            Entity ent = type.create(level);
            if (!(ent instanceof Mob mob)) { if (ent != null) ent.discard(); continue; }

            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
            if (!level.noCollision(mob)) { mob.discard(); continue; }

            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
            mob.setPersistenceRequired();

            AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                double v = Math.min(1000, Math.max(60, hp.getBaseValue() * BOSS_HEALTH_MULT));
                hp.setBaseValue(v);
                mob.setHealth(mob.getMaxHealth());
            }
            mob.setTarget(p);
            level.addFreshEntity(mob);

            String title = BOSS_TITLES[r.nextInt(BOSS_TITLES.length)];
            Component name = Component.literal("☠ " + title + " ☠").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
            ServerBossEvent bar = new ServerBossEvent(name,
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
            bar.setDarkenScreen(true);
            bar.setCreateWorldFog(true);
            bar.addPlayer(p);

            Boss b = new Boss();
            b.mob = mob; b.bar = bar; b.target = p;
            b.expireTick = ticks + BOSS_LIFETIME * 20L;
            BOSSES.add(b);

            p.displayClientMessage(Component.literal("Algo ha despertado...").withStyle(ChatFormatting.DARK_RED), true);
            playAt(p, SoundEvents.ENDERMAN_SCREAM, mob.getX(), mob.getY(), mob.getZ(), 1.5f, 0.5f);
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0, false, false, false));
            return true;
        }
        return false;
    }

    private static void tickBosses() {
        Iterator<Boss> it = BOSSES.iterator();
        while (it.hasNext()) {
            Boss b = it.next();
            if (b.mob.isRemoved() || !b.mob.isAlive()) {
                b.bar.removeAllPlayers();
                it.remove();
                continue;
            }
            if (ticks >= b.expireTick) {
                // se desvanece en la oscuridad
                if (b.target != null && !b.target.hasDisconnected()) {
                    playAt(b.target, SoundEvents.ENDERMAN_STARE, b.mob.getX(), b.mob.getY(), b.mob.getZ(), 1.0f, 0.5f);
                }
                b.mob.discard();
                b.bar.removeAllPlayers();
                it.remove();
                continue;
            }
            b.bar.setProgress(Mth.clamp(b.mob.getHealth() / b.mob.getMaxHealth(), 0f, 1f));
        }
    }

    // --------------------------------------------------------------- comandos
    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("terror")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("boss").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                boolean ok = spawnBoss(p);
                if (!ok) c.getSource().sendFailure(Component.literal("No encontré un sitio donde aparecer."));
                return ok ? 1 : 0;
            }))
            .then(Commands.literal("sign").executes(c -> {
                placeSign(c.getSource().getPlayerOrException());
                return 1;
            }))
            .then(Commands.literal("on").executes(c -> {
                enabled = true;
                c.getSource().sendSuccess(() -> Component.literal("Terror activado."), true);
                return 1;
            }))
            .then(Commands.literal("off").executes(c -> {
                enabled = false;
                c.getSource().sendSuccess(() -> Component.literal("Terror desactivado."), true);
                return 1;
            })));
    }
}
