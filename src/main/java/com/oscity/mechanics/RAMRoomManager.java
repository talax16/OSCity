package com.oscity.mechanics;

import com.oscity.journey.Journey;
import com.oscity.session.JourneyTracker;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the RAM room frame signs showing which process is mapped to each frame.
 * 
 * Signs are updated based on the player's current journey and phase (before/after operations).
 */
public class RAMRoomManager {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;

    public RAMRoomManager(JavaPlugin plugin, JourneyTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /**
     * Updates all RAM frame signs based on the player's current journey and phase.
     * Non-FREE frames also have a written book placed in their chest.
     */
    public void updateFrameSigns(Player player) {
        Journey journey = tracker.getJourney(player);
        if (journey == null) return;

        String phase = tracker.getPhase(player);
        FrameState[] states = getFrameStates(journey, phase);

        // Update frames 0-7
        for (int i = 0; i < 8; i++) {
            updateFrameSign(i + 1, states[i], journey);
        }

        // Update zero frame (frame 9)
        updateZeroFrameSign(states[8]);
    }

    /**
     * Updates ONLY the zero frame sign (frame 9).
     * Does NOT update frames 0-7 (preserves their chest contents).
     * Used for LAZY_ALLOCATION after swap to avoid clearing the book in chest7.
     */
    public void updateZeroFrameSignOnly(Player player) {
        Journey journey = tracker.getJourney(player);
        if (journey == null) return;

        String phase = tracker.getPhase(player);
        FrameState[] states = getFrameStates(journey, phase);

        // Update only zero frame (frame 9)
        updateZeroFrameSign(states[8]);
    }

    /**
     * Gets the frame states for a given journey and phase.
     * Returns array of 9 FrameState objects (8 frames + zero frame).
     */
    private FrameState[] getFrameStates(Journey journey, String phase) {
        FrameState[] states = new FrameState[9];

        switch (journey) {
            case LUCKY:
                states = getLuckyStates();
                break;
            case TLB_MISS_ALLOW:
                states = getTLBMissStates();
                break;
            case PERMISSION_VIOLATION:
                states = getPermissionViolationStates();
                break;
            case SWAPPED_OUT:
                states = getSwappedOutStates(phase);
                break;
            case PURE_COW:
                states = getPureCOWStates(phase);
                break;
            case LAZY_LOADING:
                states = getLazyLoadingStates(phase);
                break;
            case LAZY_ALLOCATION:
                states = getLazyAllocationStates(phase);
                break;
            default:
                states = getDefaultStates();
                break;
        }

        return states;
    }

    // ── Journey-specific frame states ─────────────────────────────────────────

    private FrameState[] getLuckyStates() {
        FrameState[] states = new FrameState[9];
        states[0] = new FrameState("0x0", "Process 3", "data");
        states[1] = new FrameState("0x1", "Process 0", "data");
        states[2] = new FrameState("0x2", "FREE", "");
        states[3] = new FrameState("0x3", "Process 1", "data");
        states[4] = new FrameState("0x4", "Process 5", "data");
        states[5] = new FrameState("0x5", "Process 6", "data");
        states[6] = new FrameState("0x6", "Process 8", "data");
        states[7] = new FrameState("0x7", "Process 9", "data");
        states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 4, 7)");
        return states;
    }

    private FrameState[] getTLBMissStates() {
        FrameState[] states = new FrameState[9];
        states[0] = new FrameState("0x0", "Process 3", "data");
        states[1] = new FrameState("0x1", "Process 0", "data");
        states[2] = new FrameState("0x2", "FREE", "");
        states[3] = new FrameState("0x3", "Process 2", "data");
        states[4] = new FrameState("0x4", "Process 5", "data");
        states[5] = new FrameState("0x5", "Process 6", "data");
        states[6] = new FrameState("0x6", "Process 8", "data");
        states[7] = new FrameState("0x7", "Process 9", "data");
        states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 1, 4, 7)");
        return states;
    }

    private FrameState[] getPermissionViolationStates() {
        // Any state is fine for permission violation
        return getDefaultStates();
    }

    private FrameState[] getSwappedOutStates(String phase) {
        FrameState[] states = new FrameState[9];
        
        if ("ram_disk_swap".equals(phase) || "ram_book_placed_swapped".equals(phase) || "swap_entered".equals(phase)) {
            // Before loading from swap
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "FREE", "");
            states[3] = new FrameState("0x3", "Process 2", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 1, 7)");
        } else {
            // After loading from swap (ram_finish or later)
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 4", "data ← Loaded from swap");
            states[3] = new FrameState("0x3", "Process 2", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 1, 7)");
        }
        
        return states;
    }

    private FrameState[] getPureCOWStates(String phase) {
        FrameState[] states = new FrameState[9];

        // Pure COW: Player always sees RAM after COW allocation (never before)
        // Frame 0x2: Process 5's private copy (created by COW)
        // Zero Frame: No longer shared with Process 5 (they have their own copy now)
        states[0] = new FrameState("0x0", "Process 3", "data");
        states[1] = new FrameState("0x1", "Process 0", "data");
        states[2] = new FrameState("0x2", "Process 5", "data");
        states[3] = new FrameState("0x3", "Process 1", "data");
        states[4] = new FrameState("0x4", "Process 4", "data");
        states[5] = new FrameState("0x5", "Process 6", "data");
        states[6] = new FrameState("0x6", "Process 8", "data");
        states[7] = new FrameState("0x7", "Process 9", "data");
        states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 7)");

        return states;
    }

    private FrameState[] getLazyLoadingStates(String phase) {
        FrameState[] states = new FrameState[9];
        
        if ("ram_disk_lazy_loading".equals(phase)) {
            // Before swap - RAM full
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 1", "data");
            states[3] = new FrameState("0x3", "Process 4", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 7)");
        } else if ("ram_after_swap_lazy_loading".equals(phase) || "swap_after_eviction".equals(phase)) {
            // After eviction, before load
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 1", "data");
            states[3] = new FrameState("0x3", "Process 4", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "FREE", "0x5");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 7)");
        } else {
            // After load from disk
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 1", "data");
            states[3] = new FrameState("0x3", "Process 4", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 7)");
        }
        
        return states;
    }

    private FrameState[] getLazyAllocationStates(String phase) {
        FrameState[] states = new FrameState[9];
        
        if ("ram_after_cow_alloc".equals(phase)) {
            // First RAM visit - full RAM, before swap
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 3", "data");
            states[3] = new FrameState("0x3", "Process 1", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 8", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 4)");
        } else if ("ram_after_swap_lazy_alloc".equals(phase)) {
            // After swap, frame 0x6 evicted — Process 7 about to get private frame
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 3", "data");
            states[3] = new FrameState("0x3", "Process 1", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "FREE", "");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 4)");
        } else {
            // Phase 2 - After COW + Write
            states[0] = new FrameState("0x0", "Process 3", "data");
            states[1] = new FrameState("0x1", "Process 0", "data");
            states[2] = new FrameState("0x2", "Process 3", "data");
            states[3] = new FrameState("0x3", "Process 1", "data");
            states[4] = new FrameState("0x4", "Process 5", "data");
            states[5] = new FrameState("0x5", "Process 6", "data");
            states[6] = new FrameState("0x6", "Process 7", "data");
            states[7] = new FrameState("0x7", "Process 9", "data");
            states[8] = new FrameState("0x9", "ZERO FRAME", "(shared by Process 2, 4)");
        }
        
        return states;
    }

    private FrameState[] getDefaultStates() {
        FrameState[] states = new FrameState[9];
        states[0] = new FrameState("0x0", "Process 3", "data");
        states[1] = new FrameState("0x1", "Process 0", "data");
        states[2] = new FrameState("0x2", "FREE", "");
        states[3] = new FrameState("0x3", "Process 1", "data");
        states[4] = new FrameState("0x4", "Process 5", "data");
        states[5] = new FrameState("0x5", "Process 6", "data");
        states[6] = new FrameState("0x6", "Process 8", "data");
        states[7] = new FrameState("0x7", "Process 9", "data");
        states[8] = new FrameState("0x9", "ZERO FRAME", "(shared)");
        return states;
    }

    // ── Sign update helpers ───────────────────────────────────────────────────

    private void updateFrameSign(int frameNum, FrameState state, Journey journey) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("signs.ramRoom.frame" + frameNum);
        if (sec == null) {
            plugin.getLogger().warning("[RAMRoom] No config for signs.ramRoom.frame" + frameNum);
            return;
        }

        updateSign(sec, "Frame " + state.pfn + ":", state.process, state.status, "");

        ConfigurationSection chestSec = plugin.getConfig()
            .getConfigurationSection("chests.ramRoom.chest" + frameNum);
        if ("FREE".equals(state.process)) {
            clearChest(chestSec);
        } else {
            placeBookInChest(chestSec, buildRamFrameBook(state, journey));
        }
    }

    private void updateZeroFrameSign(FrameState state) {
        // Update zeroFrame1 sign (top at y=-58) - Frame label and process name
        ConfigurationSection sec1 = plugin.getConfig()
            .getConfigurationSection("signs.ramRoom.zeroFrame1");
        if (sec1 != null) {
            updateSign(sec1, "Frame " + state.pfn + ":", state.process, "", "");
        } else {
            plugin.getLogger().warning("[RAMRoom] No config for signs.ramRoom.zeroFrame1");
        }

        // Update zeroFrame2 sign (bottom at y=-59) - Status text
        // Split long status text across multiple lines to fit on wall sign
        ConfigurationSection sec2 = plugin.getConfig()
            .getConfigurationSection("signs.ramRoom.zeroFrame2");
        if (sec2 != null) {
            String status = state.status;

            // Split status like "(shared by Process 2, 4)" across 2 lines
            if (status != null && status.startsWith("(shared by")) {
                // Extract the part after "(shared by " and split across lines
                String sharedPart = status.substring("(shared by ".length());
                updateSign(sec2, "(shared by", sharedPart, "", "");
            } else {
                updateSign(sec2, status, "", "", "");
            }
        } else {
            plugin.getLogger().warning("[RAMRoom] No config for signs.ramRoom.zeroFrame2");
        }

        ConfigurationSection chestSec = plugin.getConfig()
            .getConfigurationSection("chests.ramRoom.zeroChest");
        if ("FREE".equals(state.process)) {
            clearChest(chestSec);
        } else {
            placeBookInChest(chestSec, buildZeroFrameBook());
        }
    }

    /**
     * Places the Process 5 private copy book in frame 0x2 chest for Pure COW journey,
     * or the COW page book for LAZY_ALLOCATION journey after swap.
     * Uses a WRITABLE_BOOK (book and quill) so the player can edit it.
     * Book contains only zeros (copied from Zero Frame).
     */
    public void placeBookInFrameChest(Player player, int frameNum) {
        Journey journey = tracker.getJourney(player);
        if (journey != Journey.PURE_COW && journey != Journey.LAZY_ALLOCATION) {
            plugin.getLogger().warning("[RAMRoom] placeBookInFrameChest: Not PURE_COW or LAZY_ALLOCATION journey, got " + journey);
            return;
        }

        ConfigurationSection chestSec = plugin.getConfig()
            .getConfigurationSection("chests.ramRoom.chest" + frameNum);
        if (chestSec == null) {
            plugin.getLogger().warning("[RAMRoom] placeBookInFrameChest: No config for chest" + frameNum);
            return;
        }

        placeBookInChest(chestSec, buildProcess5WritableBook());
    }

    private ItemStack buildProcess5WritableBook() {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            // Add initial zeros content (copied from Zero Frame)
            meta.addPage(
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n" +
                "0 0 0 0 0 0 0 0\n\n" 
            );
            book.setItemMeta(meta);
        }
        return book;
    }

    private void placeBookInChest(ConfigurationSection sec, ItemStack book) {
        if (sec == null) {
            plugin.getLogger().warning("[RAMRoom] placeBookInChest: sec is null");
            return;
        }
        if (book == null || book.getType() == Material.AIR) {
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) {
            plugin.getLogger().warning("[RAMRoom] placeBookInChest: No world in config");
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[RAMRoom] placeBookInChest: World not found: " + worldName);
            return;
        }

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Chest chest)) {
            plugin.getLogger().warning("[RAMRoom] placeBookInChest: No chest at config path (found: " + block.getType() + ")");
            return;
        }

        Inventory inv = chest.getInventory();
        inv.clear();
        inv.setItem(13, book);
    }

    private ItemStack buildRamFrameBook(FrameState state, Journey journey) {
        if ("0x3".equals(state.pfn) && journey == Journey.LUCKY) {
            return buildBook(
                "Frame Record",
                "The trail was clear from the first step.\n\n"
                + "The markings aligned, and the passage opened at once."
                + "Nothing faltered, nothing was questioned.\n\n"
                + "The answer lay exactly where it was meant to be. \n\n"
                + "Frame 0x3 | Process 1 | Resident"
            );
        } else if ("0x3".equals(state.pfn) && journey == Journey.TLB_MISS_ALLOW) {
            return buildBook(
                "Frame Record",
                "The first map bore no mark of this place.\n\n"
                + "For a moment, it seemed forgotten.\n\n"
                + "Yet deeper in the archive, its record remained intact.\n\n"
                + "Once found, the path stood clear as if it had never been missing.\n\n"
                + "Frame 0x3 | Process 2 | Resident"
            );
        } else if ("0x2".equals(state.pfn) && journey == Journey.PURE_COW
                && "Process 5".equals(state.process)) {
            // Pure COW: Frame 0x2 book is placed separately as WRITABLE_BOOK by placeBookInFrameChest()
            // Return AIR to skip placing a book here
            return new ItemStack(Material.AIR);
        } else {
            return buildBook(
                "Frame Record",
                "This record concerns a distant province.\n\n"
                + "Its markings bear no relation to the current inquiry.\n\n"
                + "The routes described here lead elsewhere.\n\n"
                + "No guidance for your path lies within these lines.\n\n"
                + "Frame " + state.pfn + " | " + state.process + "\n"
                + "Status: Resident\n\n"
            );
        }
    }

    private ItemStack buildZeroFrameBook() {
        String zeros = "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0\n"
            + "0 0 0 0 0 0 0 0";
        return buildBook(
            "Zero Frame",
            zeros
        );
    }

    private ItemStack buildBook(String title, String... pages) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle(title);
            meta.setAuthor("OS City");
            meta.setGeneration(BookMeta.Generation.ORIGINAL);
            List<Component> pageComponents = new ArrayList<>();
            for (String page : pages) {
                pageComponents.add(Component.text(page));
            }
            meta.pages(pageComponents);
            book.setItemMeta(meta);
        }
        return book;
    }

    private void clearChest(ConfigurationSection sec) {
        if (sec == null) return;
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();
        if (block.getState() instanceof Chest chest) {
            chest.getInventory().clear();
        }
    }

    private void updateSign(ConfigurationSection sec, String line1, String line2, String line3, String line4) {
        String worldName = sec.getString("world");
        if (worldName == null) {
            plugin.getLogger().warning("[RAMRoom] updateSign: No world in config for " + sec.getCurrentPath());
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[RAMRoom] updateSign: World not found: " + worldName);
            return;
        }

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) {
            plugin.getLogger().warning("[RAMRoom] updateSign: No sign at " + sec.getCurrentPath() + " (found: " + block.getType() + ")");
            return;
        }

        sign.getSide(Side.FRONT).line(0, Component.text(line1));
        sign.getSide(Side.FRONT).line(1, Component.text(line2));
        sign.getSide(Side.FRONT).line(2, Component.text(line3));
        sign.getSide(Side.FRONT).line(3, Component.text(line4));
        sign.update(true);
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    private static class FrameState {
        final String pfn;
        final String process;
        final String status;

        FrameState(String pfn, String process, String status) {
            this.pfn = pfn;
            this.process = process;
            this.status = status;
        }
    }
}
