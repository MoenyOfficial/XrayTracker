package me.xraid.xraytracker;

import me.xraid.xraytracker.models.MiningRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PathHighlighter {
    private static final int MIN_VISIBILITY_SECONDS = 10;
    private static final int DEFAULT_REFRESH_TICKS = 15;
    private static final int DEFAULT_MAX_PARTICLE_POINTS = 140;
    private static final double LINE_STEP_BLOCKS = 1.2D;

    private static final Set<ArmorStand> activeReplayStands = ConcurrentHashMap.newKeySet();
    private final Map<UUID, HighlightSession> activeSessions = new HashMap<>();

    public static void cleanUpAllStands() {
        for (ArmorStand stand : activeReplayStands) {
            if (stand != null && stand.isValid()) {
                stand.remove();
            }
        }
        activeReplayStands.clear();
    }

    public void highlight(Player admin, List<MiningRecord> records) {
        if (records == null || records.isEmpty()) {
            admin.sendMessage("§c[XrayTracker] No mining records found for this player.");
            return;
        }

        // Cancel previous session if any
        stopHighlight(admin, false);

        // Teleport to the start of the path
        World firstWorld = Bukkit.getWorld(records.get(0).world());
        if (firstWorld != null) {
            Location startLoc = new Location(firstWorld, records.get(0).x() + 0.5, records.get(0).y() + 1.0, records.get(0).z() + 0.5);
            admin.teleport(startLoc);
            admin.playSound(admin.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
            admin.sendMessage("§6[XrayTracker] §7Teleported to path start.");
        }

        int keepSeconds = Math.max(MIN_VISIBILITY_SECONDS, XrayTracker.getInstance().getConfig().getInt("path-visibility-seconds", 90));
        int maxPoints = Math.max(20, XrayTracker.getInstance().getConfig().getInt("path-particle-max-points", DEFAULT_MAX_PARTICLE_POINTS));
        int stride = Math.max(1, (int) Math.ceil((double) records.size() / maxPoints));

        HighlightSession session = new HighlightSession();
        activeSessions.put(admin.getUniqueId(), session);

        admin.playSound(admin.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
        admin.sendMessage("§6[XrayTracker] §7Showing path of §e" + records.size() + " §7records for §e" + keepSeconds + "s§7. §8(Type /xt stop to clear)");

        // Render the path client-side EXACTLY ONCE (prevents packet spam and client FPS drops)
        renderPath(admin, records, stride, session.changedLocations);

        // Run countdown timer for the Action Bar HUD
        BukkitTask task = new BukkitRunnable() {
            int secondsLeft = keepSeconds;

            @Override
            public void run() {
                if (secondsLeft <= 0 || !admin.isOnline()) {
                    cancel();
                    stopHighlight(admin, true);
                    return;
                }

                admin.sendActionBar(net.kyori.adventure.text.Component.text(
                    "§6§lXray Path §8| §e" + secondsLeft + "s remaining §8(Use §c/xt stop§8 to clear)"
                ));

                secondsLeft--;
            }
        }.runTaskTimer(XrayTracker.getInstance(), 0L, 20L);

        session.task = task;
    }

    public void startPlayback(Player admin, List<MiningRecord> records, String targetIdentifier, String displayName) {
        if (records == null || records.isEmpty()) {
            admin.sendMessage("§c[XrayTracker] No mining records found for this player.");
            return;
        }

        // Cancel previous session if any
        stopHighlight(admin, false);

        // Teleport to the start of the path
        World world = Bukkit.getWorld(records.get(0).world());
        if (world == null) {
            admin.sendMessage("§c[XrayTracker] Target world not loaded.");
            return;
        }

        Location startLoc = new Location(world, records.get(0).x() + 0.5, records.get(0).y(), records.get(0).z() + 0.5);
        
        // Explicitly load the chunk to avoid entity spawn failure/despawning
        startLoc.getChunk().load();

        // Teleport admin slightly higher so they can see the stand clearly
        Location adminLoc = new Location(world, records.get(0).x() + 0.5, records.get(0).y() + 1.5, records.get(0).z() + 0.5);
        admin.teleport(adminLoc);
        admin.playSound(admin.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        admin.sendMessage("§6[XrayTracker] §7Teleported to path start.");

        // Spawn Replay Armor Stand (Miner Ghost)
        ArmorStand stand = world.spawn(startLoc, ArmorStand.class);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setInvisible(true); // wooden stand itself is invisible
        stand.setGravity(false);
        stand.setSmall(true); // make a small miner stand
        stand.setMarker(true); // pass through blocks without collision

        // Equip Helmet (Player Head)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        if (headMeta != null) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(targetIdentifier));
                headMeta.setOwningPlayer(op);
            } catch (Exception e) {
                headMeta.setOwner(displayName);
            }
            head.setItemMeta(headMeta);
        }
        stand.getEquipment().setHelmet(head);

        // Equip Pickaxe & Armor
        stand.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
        stand.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        stand.getEquipment().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        stand.getEquipment().setBoots(new ItemStack(Material.IRON_BOOTS));

        // Hide stand from all other players
        activeReplayStands.add(stand);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != admin) {
                p.hideEntity(XrayTracker.getInstance(), stand);
            }
        }

        HighlightSession session = new HighlightSession();
        session.stand = stand;
        session.records = records;
        session.targetIdentifier = targetIdentifier;
        session.displayName = displayName;
        session.world = world;
        session.isAnimatedReplay = true;
        session.recordIndex = 0;
        session.subStep = 0;
        session.ticksPerStep = 8;
        session.fromLoc = startLoc.clone();
        session.toLoc = startLoc.clone();
        session.paused = false;

        activeSessions.put(admin.getUniqueId(), session);

        // Client-side restore all ores at the start of the replay
        syncReplayBlocks(admin, session);

        // Send Control Panel to Chat
        net.kyori.adventure.text.Component panel = net.kyori.adventure.text.Component.text("\n§6§l=== XRAY REPLAY CONTROLS ===\n")
            .append(net.kyori.adventure.text.Component.text(" §7Target: §e" + displayName + " §8| §7Ores: §b" + records.size() + "\n\n"))
            .append(net.kyori.adventure.text.Component.text("  §b[ ⏪ Rewind ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Go back 1 step")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol rewind")))
            .append(net.kyori.adventure.text.Component.text("  §a[ ⏸ Pause/Play ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Pause or resume replay")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol toggle")))
            .append(net.kyori.adventure.text.Component.text("  §b[ ⏩ Skip ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Skip to next step")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol skip")))
            .append(net.kyori.adventure.text.Component.text("  §e[ ⚡ Speed ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Cycle speed (0.5x, 1x, 2x, 4x)")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol speed")))
            .append(net.kyori.adventure.text.Component.text("  §d[ 🔄 Restart ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Restart from start")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol restart")))
            .append(net.kyori.adventure.text.Component.text("  §c[ ❌ Stop ] ")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§7Exit replay mode")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt replaycontrol stop")))
            .append(net.kyori.adventure.text.Component.text("\n§6§l============================\n"));

        admin.sendMessage(panel);
        admin.playSound(admin.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);

        BukkitTask task = new BukkitRunnable() {
            private int debugTicks = 0;

            @Override
            public void run() {
                try {
                    if (!admin.isOnline() || !stand.isValid()) {
                        cancel();
                        stopHighlight(admin, true);
                        return;
                    }

                    // Console debug logging to trace exact replay loop variables
                    if (debugTicks++ % 10 == 0) {
                        Bukkit.getLogger().info("[XrayTracker] Replay Tick: recordIndex=" + session.recordIndex 
                            + ", subStep=" + session.subStep + ", paused=" + session.paused 
                            + ", standLoc=" + stand.getLocation().toVector().toString());
                    }

                    // If paused, we do nothing but update the action bar to show paused state
                    if (session.paused) {
                        admin.sendActionBar(net.kyori.adventure.text.Component.text(
                            "§6§lXray Replay §8| §c§lPAUSED §8| §fStep §e" + (session.recordIndex + 1) + "§7/§e" + session.records.size()
                        ));
                        return;
                    }

                    // If finished with all records
                    if (session.recordIndex >= session.records.size()) {
                        cancel();
                        admin.sendMessage("§6[XrayTracker] §7Animated path replay completed.");
                        admin.playSound(admin.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.0f);
                        stopHighlight(admin, false);
                        return;
                    }

                    MiningRecord currentRecord = session.records.get(session.recordIndex);

                    // Setup next destination if subStep resets
                    if (session.subStep == 0) {
                        if (session.recordIndex == 0) {
                            session.fromLoc = startLoc.clone();
                        } else {
                            MiningRecord prev = session.records.get(session.recordIndex - 1);
                            World prevWorld = Bukkit.getWorld(prev.world());
                            if (prevWorld == null) prevWorld = session.world;
                            session.fromLoc = new Location(prevWorld, prev.x() + 0.5, prev.y(), prev.z() + 0.5);
                        }
                        
                        World curWorld = Bukkit.getWorld(currentRecord.world());
                        if (curWorld == null) curWorld = session.world;
                        session.toLoc = new Location(curWorld, currentRecord.x() + 0.5, currentRecord.y(), currentRecord.z() + 0.5);
                        session.world = curWorld; // Keep session world in sync with current record's world

                        // Ensure chunks are loaded before gliding
                        session.fromLoc.getChunk().load();
                        session.toLoc.getChunk().load();

                        // If far apart (teleported/caved) or in a different world, jump instantly
                        if (!session.fromLoc.getWorld().equals(session.toLoc.getWorld()) || session.fromLoc.distance(session.toLoc) > 10.0D) {
                            stand.teleport(lookAt(session.toLoc, session.toLoc));
                            session.fromLoc = session.toLoc.clone();
                            admin.teleport(session.toLoc.clone().add(0, 1.5, 0)); // Teleport admin along
                            admin.playSound(session.toLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
                        }
                    }

                    // Prevent overshooting if speed was changed mid-glide
                    if (session.subStep > session.ticksPerStep) {
                        session.subStep = session.ticksPerStep;
                    }

                    // Interpolate glide position
                    double fraction = (double) session.subStep / session.ticksPerStep;
                    double x = session.fromLoc.getX() + (session.toLoc.getX() - session.fromLoc.getX()) * fraction;
                    double y = session.fromLoc.getY() + (session.toLoc.getY() - session.fromLoc.getY()) * fraction;
                    double z = session.fromLoc.getZ() + (session.toLoc.getZ() - session.fromLoc.getZ()) * fraction;
                    Location midLoc = new Location(session.world, x, y, z);
                    
                    // Teleport stand looking at destination
                    boolean success = stand.teleport(lookAt(midLoc, session.toLoc));
                    if (!success && debugTicks % 10 == 0) {
                        Bukkit.getLogger().warning("[XrayTracker] Teleportation of miner stand failed! (Cancelled by another plugin?)");
                    }

                    // Animate Pickaxe Arm Swing (arm angle ranges between -40 and 40 degrees)
                    double cycleFraction = (double) (session.subStep % 4) / 3.0; // cycle arm pose every 4 ticks
                    double swingProgress = Math.sin(cycleFraction * Math.PI); // 0 to 1
                    double xRot = Math.toRadians(-40.0 + 80.0 * swingProgress); // swing up and down
                    double yRot = Math.toRadians(-15.0 * swingProgress); // slight inward rotation
                    double zRot = Math.toRadians(10.0 * swingProgress); // slight side rotation
                    stand.setRightArmPose(new EulerAngle(xRot, yRot, zRot));

                    // Head look down slightly when mining
                    stand.setHeadPose(new EulerAngle(Math.toRadians(15.0), 0.0, 0.0));

                    // Spawn trace particles
                    admin.spawnParticle(Particle.CRIT, midLoc, 1, 0.0, 0.0, 0.0, 0.0);

                    // Arrived at the record destination (fraction 1.0)
                    if (session.subStep >= session.ticksPerStep) {
                        Location blockLoc = new Location(session.world, currentRecord.x(), currentRecord.y(), currentRecord.z());
                        
                        // Draw client block updates (reveal mined blocks to admin)
                        Material blockMat = Material.matchMaterial(currentRecord.blockType());
                        if (blockMat == null) blockMat = Material.STONE;
                        
                        admin.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                        session.changedLocations.add(blockLoc);

                        // Block break sound & particles
                        admin.playSound(blockLoc, Sound.BLOCK_STONE_BREAK, 0.8f, 1.0f);
                        admin.spawnParticle(Particle.BLOCK, blockLoc.clone().add(0.5, 0.5, 0.5), 12, 0.2, 0.2, 0.2, blockMat.createBlockData());

                        // Render lines if connected
                        if (session.previousRecord != null && session.previousRecord.world().equals(currentRecord.world())) {
                            drawLine(admin, session.previousRecord, currentRecord, session.changedLocations);
                        }

                        // Update action bar UI tracker
                        double speedMult = 8.0 / session.ticksPerStep;
                        String speedStr = speedMult == 1.0 ? "" : " §8[§e" + speedMult + "x§8]";
                        admin.sendActionBar(net.kyori.adventure.text.Component.text(
                            "§d§lMiner Replay" + speedStr + " §8| §fStep §e" + (session.recordIndex + 1) + "§7/§e" + session.records.size() + " §8| §b" + currentRecord.blockType()
                        ));

                        session.previousRecord = currentRecord;
                        session.recordIndex++;
                        session.subStep = 0; // reset for next target
                    } else {
                        session.subStep++;
                    }
                } catch (Throwable t) {
                    Bukkit.getLogger().severe("[XrayTracker] Exception in replay loop task:");
                    t.printStackTrace();
                    cancel();
                    stopHighlight(admin, true);
                }
            }
        }.runTaskTimer(XrayTracker.getInstance(), 0L, 2L); // tick every 2 ticks (0.1s) for high smoothness

        session.task = task;
    }

    public void togglePause(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        if (session == null || !session.isAnimatedReplay) {
            admin.sendMessage("§c[XrayTracker] No active replay session.");
            return;
        }
        session.paused = !session.paused;
        if (session.paused) {
            admin.sendMessage("§6[XrayTracker] §eReplay paused. §7(Click in chat to resume)");
            admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
        } else {
            admin.sendMessage("§6[XrayTracker] §aReplay resumed.");
            admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    public void adjustSpeed(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        if (session == null || !session.isAnimatedReplay) {
            admin.sendMessage("§c[XrayTracker] No active replay session.");
            return;
        }
        // Speed cycles: 8 ticks (1x) -> 4 ticks (2x) -> 2 ticks (4x) -> 16 ticks (0.5x)
        if (session.ticksPerStep == 8) {
            session.ticksPerStep = 4;
            admin.sendMessage("§6[XrayTracker] §7Speed set to §e2.0x§7.");
        } else if (session.ticksPerStep == 4) {
            session.ticksPerStep = 2;
            admin.sendMessage("§6[XrayTracker] §7Speed set to §e4.0x§7.");
        } else if (session.ticksPerStep == 2) {
            session.ticksPerStep = 16;
            admin.sendMessage("§6[XrayTracker] §7Speed set to §e0.5x§7.");
        } else {
            session.ticksPerStep = 8;
            admin.sendMessage("§6[XrayTracker] §7Speed set to §e1.0x§7.");
        }
        admin.playSound(admin.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
    }

    public void skipForward(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        if (session == null || !session.isAnimatedReplay) {
            admin.sendMessage("§c[XrayTracker] No active replay session.");
            return;
        }
        if (session.recordIndex >= session.records.size() - 1) {
            admin.sendMessage("§c[XrayTracker] Already at the end of the replay.");
            return;
        }
        session.recordIndex++;
        session.subStep = 0;
        session.previousRecord = session.records.get(session.recordIndex - 1);
        
        // Sync the blocks to match the new recordIndex
        syncReplayBlocks(admin, session);
        
        // Teleport the stand to the new block
        MiningRecord r = session.records.get(session.recordIndex);
        World targetWorld = Bukkit.getWorld(r.world());
        if (targetWorld == null) targetWorld = session.world;
        session.world = targetWorld; // Update session world
        
        Location newLoc = new Location(targetWorld, r.x() + 0.5, r.y(), r.z() + 0.5);
        newLoc.getChunk().load(); // Load chunk
        
        // If different world, teleport admin along
        if (!session.stand.getWorld().equals(targetWorld)) {
            admin.teleport(newLoc.clone().add(0, 1.5, 0));
        }
        
        session.stand.teleport(lookAt(session.stand.getLocation(), newLoc));
        session.fromLoc = newLoc.clone();
        
        admin.sendMessage("§6[XrayTracker] §7Skipped forward to step §e" + (session.recordIndex + 1) + "§7.");
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
    }

    public void skipBackward(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        if (session == null || !session.isAnimatedReplay) {
            admin.sendMessage("§c[XrayTracker] No active replay session.");
            return;
        }
        if (session.recordIndex <= 0) {
            admin.sendMessage("§c[XrayTracker] Already at the start of the replay.");
            return;
        }
        session.recordIndex--;
        session.subStep = 0;
        session.previousRecord = (session.recordIndex > 0) ? session.records.get(session.recordIndex - 1) : null;
        
        // Sync the blocks to match the new recordIndex
        syncReplayBlocks(admin, session);
        
        // Teleport the stand to the new block
        MiningRecord r = session.records.get(session.recordIndex);
        World targetWorld = Bukkit.getWorld(r.world());
        if (targetWorld == null) targetWorld = session.world;
        session.world = targetWorld; // Update session world
        
        Location newLoc = new Location(targetWorld, r.x() + 0.5, r.y(), r.z() + 0.5);
        newLoc.getChunk().load(); // Load chunk
        
        // If different world, teleport admin along
        if (!session.stand.getWorld().equals(targetWorld)) {
            admin.teleport(newLoc.clone().add(0, 1.5, 0));
        }
        
        session.stand.teleport(lookAt(session.stand.getLocation(), newLoc));
        session.fromLoc = newLoc.clone();
        
        admin.sendMessage("§6[XrayTracker] §7Rewound to step §e" + (session.recordIndex + 1) + "§7.");
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.9f);
    }

    public void restartReplay(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        if (session == null || !session.isAnimatedReplay) {
            admin.sendMessage("§c[XrayTracker] No active replay session.");
            return;
        }
        session.recordIndex = 0;
        session.subStep = 0;
        session.previousRecord = null;
        
        // Sync blocks
        syncReplayBlocks(admin, session);
        
        // Teleport stand to start
        MiningRecord startRecord = session.records.get(0);
        World targetWorld = Bukkit.getWorld(startRecord.world());
        if (targetWorld == null) targetWorld = session.world;
        session.world = targetWorld; // Update session world
        
        Location startLoc = new Location(targetWorld, startRecord.x() + 0.5, startRecord.y(), startRecord.z() + 0.5);
        startLoc.getChunk().load(); // Load chunk
        
        // If different world, teleport admin along
        if (!session.stand.getWorld().equals(targetWorld)) {
            admin.teleport(startLoc.clone().add(0, 1.5, 0));
        }
        
        session.stand.teleport(startLoc);
        session.fromLoc = startLoc.clone();
        
        admin.sendMessage("§6[XrayTracker] §7Restarted replay from the beginning.");
        admin.playSound(admin.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
    }

    public boolean hasActiveReplay(Player admin) {
        HighlightSession session = activeSessions.get(admin.getUniqueId());
        return session != null && session.isAnimatedReplay;
    }

    public void syncReplayBlocks(Player admin, HighlightSession session) {
        if (session.records == null || session.records.isEmpty()) return;
        for (int i = 0; i < session.records.size(); i++) {
            MiningRecord r = session.records.get(i);
            World recWorld = Bukkit.getWorld(r.world());
            if (recWorld == null) continue;
            Location loc = new Location(recWorld, r.x(), r.y(), r.z());
            if (i < session.recordIndex) {
                // Mined blocks -> set to AIR client-side
                admin.sendBlockChange(loc, Material.AIR.createBlockData());
            } else {
                // Unmined blocks (restore to recorded ore block type)
                Material blockMat = Material.matchMaterial(r.blockType());
                if (blockMat == null) blockMat = Material.STONE;
                admin.sendBlockChange(loc, blockMat.createBlockData());
            }
            session.changedLocations.add(loc);
        }
    }

    public void stopHighlight(Player admin, boolean notify) {
        HighlightSession session = activeSessions.remove(admin.getUniqueId());
        if (session != null) {
            if (session.task != null) {
                try {
                    session.task.cancel();
                } catch (IllegalStateException ignored) {}
            }
            
            // Remove replay armor stand
            if (session.stand != null) {
                activeReplayStands.remove(session.stand);
                if (session.stand.isValid()) {
                    session.stand.remove();
                }
            }

            // Revert all blocks
            for (Location loc : session.changedLocations) {
                if (loc.getWorld() != null) {
                    admin.sendBlockChange(loc, loc.getBlock().getBlockData());
                }
            }
            
            admin.sendActionBar(net.kyori.adventure.text.Component.text("§cPath highlight cleared."));
            if (notify) {
                admin.playSound(admin.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.5f);
                admin.sendMessage("§6[XrayTracker] §7Replay path cleared and reverted.");
            }
        } else if (notify) {
            admin.sendMessage("§c[XrayTracker] You do not have any active path highlight.");
        }
    }

    public static void hideReplayEntitiesFrom(Player player) {
        for (ArmorStand stand : activeReplayStands) {
            if (stand.isValid()) {
                player.hideEntity(XrayTracker.getInstance(), stand);
            }
        }
    }

    private void renderPath(Player admin, List<MiningRecord> records, int stride, Set<Location> changedLocations) {
        MiningRecord previous = null;
        for (int i = 0; i < records.size(); i += stride) {
            MiningRecord current = records.get(i);
            renderPoint(admin, current, changedLocations);

            if (previous != null && previous.world().equals(current.world())) {
                drawLine(admin, previous, current, changedLocations);
            }
            previous = current;
        }

        // Always render the latest/final point
        MiningRecord last = records.get(records.size() - 1);
        if (previous != null && previous != last) {
            renderPoint(admin, last, changedLocations);
            if (previous.world().equals(last.world())) {
                drawLine(admin, previous, last, changedLocations);
            }
        }
    }

    private void renderPoint(Player admin, MiningRecord record, Set<Location> changedLocations) {
        World world = Bukkit.getWorld(record.world());
        if (world == null) return;

        double x = record.x() + 0.5;
        double y = record.y() + 0.5;
        double z = record.z() + 0.5;

        Location blockLoc = new Location(world, record.x(), record.y(), record.z());
        BlockData data = Material.AIR.createBlockData();
        String bt = record.blockType();
        if (bt != null && !bt.isEmpty()) {
            Material m = Material.matchMaterial(bt);
            if (m != null) {
                data = m.createBlockData();
            }
        }
        
        admin.sendBlockChange(blockLoc, data);
        changedLocations.add(blockLoc);

        // Spawn beautiful glow particles
        admin.spawnParticle(Particle.GLOW, x, y, z, 2, 0.1, 0.1, 0.1, 0.0);
    }

    private void drawLine(Player admin, MiningRecord from, MiningRecord to, Set<Location> changedLocations) {
        double x1 = from.x() + 0.5;
        double y1 = from.y() + 0.5;
        double z1 = from.z() + 0.5;
        double x2 = to.x() + 0.5;
        double y2 = to.y() + 0.5;
        double z2 = to.z() + 0.5;

        World world = Bukkit.getWorld(from.world());
        if (world == null) return;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.1D) return;

        int steps = Math.max(1, (int) Math.ceil(dist / LINE_STEP_BLOCKS));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + dx * t;
            double y = y1 + dy * t;
            double z = z1 + dz * t;
            
            Location blockLoc = new Location(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (blockLoc.getBlock().getType().isSolid()) {
                admin.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                changedLocations.add(blockLoc);
            }
            admin.spawnParticle(Particle.CRIT, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private Location lookAt(Location from, Location to) {
        if (from == null || to == null) return from;
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        
        Location loc = from.clone();
        if (dx == 0 && dz == 0) {
            loc.setPitch(dy > 0 ? -90.0f : 90.0f);
            return loc;
        }
        
        double dxz = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(Math.atan2(-dy, dxz));
        
        loc.setYaw((float) yaw);
        loc.setPitch((float) pitch);
        return loc;
    }

    private static class HighlightSession {
        private BukkitTask task;
        private ArmorStand stand;
        private final Set<Location> changedLocations = new HashSet<>();
        
        // Replay control state
        private List<MiningRecord> records;
        private int recordIndex = 0;
        private int subStep = 0;
        private int ticksPerStep = 8;
        private boolean paused = false;
        private Location fromLoc;
        private Location toLoc;
        private MiningRecord previousRecord;
        private String targetIdentifier;
        private String displayName;
        private World world;
        private boolean isAnimatedReplay = false;
    }
}
