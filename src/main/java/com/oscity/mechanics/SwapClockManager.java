package com.oscity.mechanics;

import com.oscity.OSCity;
import com.oscity.content.DialogueManager;
import com.oscity.mode.PlayerMode;
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

    private final OSCity plugin;
    private final JourneyTracker tracker;
    private final DialogueManager dialogue;

    // Per-player clock state
    private final Map<UUID, ClockState> states = new HashMap<>();

    private static class ClockState {
        final Set<Integer> roundOnePressed = new HashSet<>();
        boolean roundTwoStarted = false;
        final int victimFrameNum; // 1-6
        int wrongPresses = 0;  // Track wrong presses for achievement

        ClockState(int victimFrameNum) {
            this.victimFrameNum = victimFrameNum;
        }
    }

    public SwapClockManager(OSCity plugin, JourneyTracker tracker, DialogueManager dialogue) {
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
        startClock(player, false);
    }

    /**
     * Called when the player enters the Swap District.
     * @param player the player
     * @param isRoundTwo true if this is the second round (victim already identified)
     */
    public void startClock(Player player, boolean isRoundTwo) {
        String pfnStr = tracker.getVar(player, "pfn");
        int victimFrame = parsePfn(pfnStr);
        ClockState state = new ClockState(victimFrame);
        states.put(player.getUniqueId(), state);

        if (isRoundTwo) {
            // Round 2: Victim frame already identified - torch OFF, sign shows VICTIM
            for (int i = 1; i <= 6; i++) {
                String pfnHex = "0x" + Integer.toHexString(i).toUpperCase();
                if (i == victimFrame) {
                    // Victim frame: torch OFF, sign shows VICTIM
                    setTorchLit(i, false);
                    updateFrameSign(i, pfnHex, "VICTIM!", "Pull the", "lever!");
                } else {
                    // Other frames: torch ON (were recently accessed)
                    setTorchLit(i, true);
                    updateFrameSign(i, pfnHex, "USE BIT: ON", "", "");
                }
            }
            state.roundTwoStarted = true;
            player.sendMessage(plugin.getConfigManager().getMessage("clock.victim_identified"));
        } else {
            // Round 1: Light all 6 torches (USE BIT = ON)
            for (int i = 1; i <= 6; i++) {
                setTorchLit(i, true);
            }

            // Set each frame sign: PFN label + "USE BIT: ON"
            for (int i = 1; i <= 6; i++) {
                String pfnHex = "0x" + Integer.toHexString(i).toUpperCase();
                updateFrameSign(i, pfnHex, "USE BIT: ON", "", "");
            }
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
        String pfnHex = "0x" + Integer.toHexString(frameNum).toUpperCase();
        
        // Check if victim already found and player is pressing victim button again
        if ("swap_victim_found".equals(tracker.getPhase(player)) && state != null && frameNum == state.victimFrameNum) {
            // Player pressed victim button again - complete swap
            states.remove(player.getUniqueId());
            updateFrameSign(frameNum, pfnHex, "Swapped out", "to disk", "");
            tracker.setPhase(player, "swap_after_eviction");
            player.sendMessage(plugin.getConfigManager().getMessage("clock.evicted_to_swap", "{pfn}", pfnHex));
            player.sendMessage(plugin.getConfigManager().getMessage("system.frame_swapped_out",
                "{pfn}", pfnHex));
            if (tracker.getMode(player) != PlayerMode.ADVENTURER)
                dialogue.speak(player, "rooms.swap_district.after_eviction", tracker.getVars(player));
            
            // Check if perfect run (no wrong presses)
            boolean perfect = state.wrongPresses == 0;
            plugin.getAchievementManager().onSwapClockComplete(player, perfect);
            
            return true;
        }
        
        if (state == null) return false;

        if (isTorchLit(frameNum)) {
            // ── USE BIT is ON ─────────────────────────────────────────────────
            if (state.roundTwoStarted) {
                // Round 2: frame was recently used → not the victim
                // Turn off torch to show player checked this frame
                setTorchLit(frameNum, false);
                updateFrameSign(frameNum, pfnHex, "USE BIT: OFF", "(checked)", "");
                player.sendMessage(plugin.getConfigManager().getMessage("clock.recently_accessed", "{pfn}", pfnHex));
            } else {
                // Round 1: flip USE BIT OFF (give second chance)
                setTorchLit(frameNum, false);
                updateFrameSign(frameNum, pfnHex, "USE BIT: OFF", "(2nd chance)", "");
                player.sendMessage(plugin.getConfigManager().getMessage("clock.use_bit_flipped", "{pfn}", pfnHex));
                state.roundOnePressed.add(frameNum);

                if (state.roundOnePressed.size() == 6) {
                    // All 6 pressed → end of round 1.
                    // Delay round 2 activation so the transition message appears before
                    // the player can interact again (prevents skipping straight to victim).
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        state.roundTwoStarted = true;
                        plugin.getLogger().info("[SwapClock] Starting round 2, victim frame: " + state.victimFrameNum);
                        // Re-light non-victim frames (they were accessed again)
                        for (int i = 1; i <= 6; i++) {
                            if (i != state.victimFrameNum) {
                                plugin.getLogger().info("[SwapClock] Re-lighting torch " + i);
                                setTorchLit(i, true);
                                String h = "0x" + Integer.toHexString(i).toUpperCase();
                                updateFrameSign(i, h, "USE BIT: ON", "", "");
                            } else {
                                plugin.getLogger().info("[SwapClock] Keeping torch " + i + " OFF (victim)");
                            }
                        }
                        player.sendMessage(plugin.getConfigManager().getMessage("clock.all_given_second_chance"));
                        player.sendMessage(plugin.getConfigManager().getMessage("clock.walk_again"));
                    }, 5L); // 0.5-second delay before round 2 activates
                }
            }
        } else {
            // ── USE BIT is OFF ────────────────────────────────────────────────
            if (!state.roundTwoStarted) {
                // Round 1 — a torch is already off (player pressed it earlier)
                if (state.roundOnePressed.contains(frameNum)) {
                    player.sendMessage(plugin.getConfigManager().getMessage("clock.already_flipped", "{pfn}", pfnHex));
                } else {
                    // Shouldn't happen (we lit all on entry), but handle gracefully
                    player.sendMessage(plugin.getConfigManager().getMessage("clock.already_off", "{pfn}", pfnHex));
                }
            } else if (frameNum == state.victimFrameNum) {
                // Round 2, victim found! (First press - state is kept for second press)
                updateFrameSign(frameNum, pfnHex, "VICTIM!", "Press the", "button again!");
                tracker.setPhase(player, "swap_victim_found");
                player.sendMessage(plugin.getConfigManager().getMessage("system.victim_confirmed", "{pfn}", pfnHex));
                if (tracker.getMode(player) != PlayerMode.ADVENTURER)
                    dialogue.speak(player, "rooms.swap_district.victim_found", tracker.getVars(player));
            } else {
                // Round 2, off but not the victim (shouldn't occur since non-victims were re-lit)
                player.sendMessage(plugin.getConfigManager().getMessage("clock.off_not_victim", "{pfn}", pfnHex));
                // Wrong press - track for achievement
                state.wrongPresses++;
                plugin.getAchievementManager().onWrongAnswer(player, "swap_clock");
            }
        }
        return true;
    }

    // ── Lever pull removed ────────────────────────────────────────────────────
    // Lever mechanic replaced with button press on victim frame.
    // handleLeverPull() is no longer used.

    // ── Torch helpers ─────────────────────────────────────────────────────────

    private boolean isTorchLit(int frameNum) {
        Location loc = getRedstoneLocation(frameNum);
        if (loc == null) return false;
        BlockData data = loc.getBlock().getBlockData();
        return data instanceof Lightable && ((Lightable) data).isLit();
    }

    private void setTorchLit(int frameNum, boolean lit) {
        Location loc = getRedstoneLocation(frameNum);
        if (loc == null) {
            plugin.getLogger().warning("[SwapClock] setTorchLit: No location for redstone" + frameNum);
            return;
        }
        Block block = loc.getBlock();
        plugin.getLogger().info("[SwapClock] setTorchLit: Frame " + frameNum + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " - Block: " + block.getType() + " - Setting lit: " + lit);
        BlockData data = block.getBlockData();
        if (data instanceof Lightable lightable) {
            lightable.setLit(lit);
            block.setBlockData(lightable, false); // false = no physics, prevents vanilla override
            plugin.getLogger().info("[SwapClock] setTorchLit: Successfully set frame " + frameNum + " to lit=" + lit);
        } else {
            plugin.getLogger().warning("[SwapClock] setTorchLit: Block at redstone" + frameNum
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
