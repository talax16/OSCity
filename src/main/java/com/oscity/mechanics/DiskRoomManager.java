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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Populates the Disk Room chests with written books when a player enters.
 *
 * File blocks A-I each receive a written book. Block C gets journey-specific
 * content based on which file the player's process is accessing. All other
 * blocks receive a generic archive entry.
 *
 * Swap slot 0 gets journey-specific content for SWAPPED_OUT; swap slot 1
 * always shows generic occupied-slot content.
 *
 * Call {@link #populateDiskChests(Player)} from RoomChangeListener when the
 * player enters the Disk Room.
 */
public class DiskRoomManager {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;

    private static final String[] FILE_BLOCKS = {"A", "B", "C", "D", "E", "F", "G", "H", "I"};

    public DiskRoomManager(JavaPlugin plugin, JourneyTracker tracker) {
        this.plugin  = plugin;
        this.tracker = tracker;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Places books in all disk room chests appropriate to the player's journey.
     */
    public void populateDiskChests(Player player) {
        Journey journey = tracker.getJourney(player);
        if (journey == null) return;

        String fileVar  = tracker.getVar(player, "file");
        String diskBlock = tracker.getVar(player, "diskBlock");

        for (String block : FILE_BLOCKS) {
            ItemStack book;
            if ("C".equals(block) && "C".equals(diskBlock)) {
                book = buildBlockCBook(fileVar);
            } else {
                book = buildGenericArchiveBook(block);
            }
            placeInChest("chests.diskRoom.chest" + block, book);
        }

        // Swap slot 0
        ItemStack swap0 = (journey == Journey.SWAPPED_OUT)
            ? buildSwapSlot0Book()
            : buildGenericSwapBook();
        placeInChest("chests.diskRoom.swapChest0", swap0);

        // Swap slot 1
        placeInChest("chests.diskRoom.swapChest1", buildSwapSlot1Book());
    }

    // ── Book content builders ─────────────────────────────────────────────────

    /** Block C — content depends on which file the process is accessing. */
    private ItemStack buildBlockCBook(String fileVar) {
        if ("treasure_map.bin".equals(fileVar)) {
            return buildBook(
                "treasure_map.bin",
                "The manuscript is correct. The chart lines match the"
                + " coordinates recorded in the page table.\n\n"
                + "File: treasure_map.bin\n"
                + "Block C | Page Index: 0\n\n"
                + "This is the page your process needs. The OS will load"
                + " it into a free RAM frame and update the page table"
                + " entry to complete the memory access."
            );
        } else if ("mystical_grimoire.spells".equals(fileVar)) {
            return buildBook(
                "mystical_grimoire.spells",
                "You have opened the correct volume. The incantations"
                + " encoded here align with the page table entry your"
                + " process is referencing.\n\n"
                + "File: mystical_grimoire.spells\n"
                + "Block C | Page Index: 0\n\n"
                + "This page must be loaded into RAM before the memory"
                + " access can complete."
            );
        } else {
            return buildBook(
                "Block C",
                "This page was never placed among the active records."
                + " It exists in the file system, waiting to be brought"
                + " into RAM for the first time.\n\n"
                + "Block C\n\n"
                + "A file-backed page. The page table marks it as"
                + " not-present. When a process accesses it, the OS must"
                + " locate it here and load it into a free frame."
            );
        }
    }

    /** Generic book for file blocks that are not the player's target block. */
    private ItemStack buildGenericArchiveBook(String block) {
        return buildBook(
            "Block " + block,
            "Archive Entry — Coastal Survey. Tidal charts revised for"
            + " the northern shoreline. These records are filed and"
            + " indexed but not currently mapped to any active process.\n\n"
            + "Block " + block + "\n\n"
            + "This disk block holds file data not relevant to the"
            + " current memory access. Continue searching."
        );
    }

    /** Swap slot 0 for the SWAPPED_OUT journey — the player's evicted page. */
    private ItemStack buildSwapSlot0Book() {
        return buildBook(
            "Swap Slot 0",
            "This entry was set aside when space grew scarce. The page"
            + " belonging to your process was evicted from RAM and"
            + " written here to free a physical frame for another process.\n\n"
            + "Swap Slot: 0\n\n"
            + "Your page is here. The OS must load it back into a free"
            + " frame in RAM and update the page table entry before the"
            + " memory access can complete."
        );
    }

    /** Swap slot 1 — always present, holds another process's page. */
    private ItemStack buildSwapSlot1Book() {
        return buildBook(
            "Swap Slot 1",
            "This page once stood where yours now belongs. It was evicted"
            + " to make room in RAM for another process. Now it waits in"
            + " swap space for its owner to request it again.\n\n"
            + "Swap Slot: 1\n\n"
            + "This slot is occupied by another process's page. It is not"
            + " the page you are looking for."
        );
    }

    /** Generic swap content for slot 0 when the journey is not SWAPPED_OUT. */
    private ItemStack buildGenericSwapBook() {
        return buildBook(
            "Swap Slot 0",
            "This swap slot is occupied by a page that was moved out of"
            + " RAM to make space for active processes.\n\n"
            + "Swap Slot: 0\n\n"
            + "This is not the page your process needs. Check the file"
            + " blocks for your file-backed page."
        );
    }

    // ── Book item helper ──────────────────────────────────────────────────────

    private ItemStack buildBook(String title, String... pages) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle(title);
            meta.setAuthor("OS City");
            meta.setGeneration(BookMeta.Generation.ORIGINAL);
            // Also set display name for inventory detection
            meta.setDisplayName("§r" + title);
            List<Component> pageComponents = new ArrayList<>();
            for (String page : pages) {
                pageComponents.add(Component.text(page));
            }
            meta.pages(pageComponents);
            book.setItemMeta(meta);
        }
        return book;
    }

    // ── Chest placement ───────────────────────────────────────────────────────

    private void placeInChest(String configPath, ItemStack item) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(configPath);
        if (sec == null) {
            plugin.getLogger().warning("[DiskRoom] No config at " + configPath);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();

        if (!(block.getState() instanceof Chest chest)) {
            plugin.getLogger().warning("[DiskRoom] No chest at " + configPath
                + " (found: " + block.getType() + ")");
            return;
        }

        Inventory inv = chest.getInventory();
        inv.clear();
        inv.setItem(13, item);
    }
}
