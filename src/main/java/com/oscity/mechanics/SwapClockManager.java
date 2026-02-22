package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.session.JourneyTracker;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drives the Clock Algorithm puzzle in the Swap District.
 *
 * Algorithm overview (2 iterations):
 *   Round 1 — all 6 torches start ON (USE BIT = 1).
 *     Player presses each frame button to flip it OFF (second chance given).
 *     After all 6 are pressed: non-victim torches relight; victim stays OFF.
 *   Round 2 — player walks again.
 *     Pressing a lit (ON) frame → "recently used, not the victim".
 *     Pressing the already-OFF frame (victim) → VICTIM FOUND.
 *     Lever is directed at the victim; pulling it triggers the eviction dialogue.
 */
public class SwapClockManager {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;
    private final DialogueManager dialogue;

    // Per-player clock state
    private final Map<UUID, ClockState> states = new HashMap<>();

    private static class ClockState {
        final Set<Integer> roundOnePressed = new HashSet<>();
        boolean roundTwoStarted = false;
        final int victimFrameNum; // 1-6

        ClockState(int victimFrameNum) {
            this.victimFrameNum = victimFrameNum;
        }
    }

    public SwapClockManager(JavaPlugin plugin, JourneyTracker tracker, DialogueManager dialogue) {
        this.plugin   = plugin;
        this.tracker  = tracker;
        this.dialogue = dialogue;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Called when the player enters the Swap District (after swapEntryVarUpdates
     * has been applied, so tracker.getVar(player,"pfn") is already correct).
     */
    public void startClock(Player player) {
        String pfnStr = tracker.getVar(player, "pfn");
        int victimFrame = parsePfn(pfnStr);
        states.put(player.getUniqueId(), new ClockState(victimFrame));

        // Light all 6 torches (USE BIT = ON)
        for (int i = 1; i <= 6; i++) {
            setTorchLit(i, true);
        }

        // Set each frame sign: PFN label + "USE BIT: ON"
        for (int i = 1; i <= 6; i++) {
            String pfnHex = "0x" + Integer.toHexString(i).toUpperCase();
            updateFrameSign(i, pfnHex, "USE BIT: ON", "", "");
        }
    }

    // ── Frame button presses ──────────────────────────────────────────────────

    /**
     * Called when the player presses one of the six frame buttons (frameNbtn).
     * @param frameNum 1–6
     * @return true if the clock was active and the press was handled.
     */
    public boolean handleFrameButton(Player player, int frameNum) {
        ClockState state = states.get(player.getUniqueId());
        if (state == null) return false;

        String pfnHex = "0x" + Integer.toHexString(frameNum).toUpperCase();

        if (isTorchLit(frameNum)) {
            // ── USE BIT is ON ─────────────────────────────────────────────────
            if (state.roundTwoStarted) {
                // Round 2: frame was recently used → not the victim
                player.sendMessage("§6[Clock] §eFrame §f" + pfnHex
                    + "§e was recently accessed — USE BIT is ON. Not the victim. Keep looking.");
            } else {
                // Round 1: flip USE BIT OFF (give second chance)
                setTorchLit(frameNum, false);
                updateFrameSign(frameNum, pfnHex, "USE BIT: OFF", "(2nd chance)", "");
                player.sendMessage("§6[Clock] §eUSE BIT for Frame §f" + pfnHex
                    + "§e flipped OFF. Second chance given. Move on.");
                state.roundOnePressed.add(frameNum);

                if (state.roundOnePressed.size() == 6) {
                    // All 6 pressed → end of round 1
                    // Re-light non-victim frames (they were accessed again)
                    state.roundTwoStarted = true;
                    for (int i = 1; i <= 6; i++) {
                        if (i != state.victimFrameNum) {
                            setTorchLit(i, true);
                            String h = "0x" + Integer.toHexString(i).toUpperCase();
                            updateFrameSign(i, h, "USE BIT: ON", "", "");
                        }
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        player.sendMessage("§6[Clock] §eYou have given every frame a second chance.");
                        player.sendMessage("§6[Clock] §eWalk the circle again. One frame did not get a second chance...");
                    }, 5L);
                }
            }
        } else {
            // ── USE BIT is OFF ────────────────────────────────────────────────
            if (!state.roundTwoStarted) {
                // Round 1 — a torch is already off (player pressed it earlier)
                if (state.roundOnePressed.contains(frameNum)) {
                    player.sendMessage("§6[Clock] §eYou already flipped Frame §f" + pfnHex
                        + "§e's USE BIT. Move on to the next frame.");
                } else {
                    // Shouldn't happen (we lit all on entry), but handle gracefully
                    player.sendMessage("§6[Clock] §eFrame §f" + pfnHex
                        + "§e has its USE BIT already OFF.");
                }
            } else if (frameNum == state.victimFrameNum) {
                // Round 2, victim found!
                states.remove(player.getUniqueId());
                updateFrameSign(frameNum, pfnHex, "VICTIM!", "Pull the", "lever!");
                tracker.setPhase(player, "swap_victim_found");
                dialogue.speak(player, "rooms.swap_district.victim_found", tracker.getVars(player));
            } else {
                // Round 2, off but not the victim (shouldn't occur since non-victims were re-lit)
                player.sendMessage("§6[Clock] §eFrame §f" + pfnHex
                    + "§e has USE BIT OFF — but this is not your victim frame. Investigate further.");
            }
        }
        return true;
    }

    // ── Lever pull ────────────────────────────────────────────────────────────

    /**
     * Called when the player pulls the eviction lever (swapLever button key).
     * Only acts when the player is in the "swap_victim_found" phase.
     * @return true if handled.
     */
    public boolean handleLeverPull(Player player) {
        if (!"swap_victim_found".equals(tracker.getPhase(player))) return false;

        Map<String, String> vars = tracker.getVars(player);
        dialogue.speak(player, "rooms.swap_district.after_eviction", vars);
        // Reset to swap_entered so handleRAMEntry processes the return correctly
        tracker.setPhase(player, "swap_entered");
        return true;
    }

    // ── Torch helpers ─────────────────────────────────────────────────────────

    private boolean isTorchLit(int frameNum) {
        Location loc = getRedstoneLocation(frameNum);
        if (loc == null) return false;
        BlockData data = loc.getBlock().getBlockData();
        return data instanceof Lightable && ((Lightable) data).isLit();
    }

    private void setTorchLit(int frameNum, boolean lit) {
        Location loc = getRedstoneLocation(frameNum);
        if (loc == null) return;
        Block block = loc.getBlock();
        BlockData data = block.getBlockData();
        if (data instanceof Lightable lightable) {
            lightable.setLit(lit);
            block.setBlockData(data);
        } else {
            plugin.getLogger().warning("[SwapClock] Block at redstone" + frameNum
                + " is not Lightable: " + data.getMaterial());
        }
    }

    private Location getRedstoneLocation(int frameNum) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("redstone.redstone" + frameNum);
        if (sec == null) {
            plugin.getLogger().warning("[SwapClock] No config for redstone.redstone" + frameNum);
            return null;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
    }

    // ── Sign helpers ──────────────────────────────────────────────────────────

    private void updateFrameSign(int frameNum, String l1, String l2, String l3, String l4) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("signs.swapDistrict.frame" + frameNum);
        if (sec == null) {
            plugin.getLogger().warning("[SwapClock] No sign config for swapDistrict.frame" + frameNum);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();

        if (!(block.getState() instanceof Sign sign)) {
            plugin.getLogger().warning("[SwapClock] No sign block at swapDistrict.frame" + frameNum
                + " (" + block.getType() + ")");
            return;
        }

        sign.getSide(Side.FRONT).line(0, Component.text(l1));
        sign.getSide(Side.FRONT).line(1, Component.text(l2));
        sign.getSide(Side.FRONT).line(2, Component.text(l3));
        sign.getSide(Side.FRONT).line(3, Component.text(l4));
        sign.update(true);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Parse "0x5" or "0xF" style PFN strings to an int (1-6 expected). */
    private int parsePfn(String pfnStr) {
        try {
            return Integer.parseInt(pfnStr.replace("0x", "").replace("0X", ""), 16);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("[SwapClock] Could not parse PFN '" + pfnStr
                + "' — defaulting victim to frame 1");
            return 1;
        }
    }
}
