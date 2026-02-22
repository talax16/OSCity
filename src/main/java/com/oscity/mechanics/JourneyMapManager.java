package com.oscity.mechanics;

import com.oscity.session.JourneyTracker;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates and manages the journey map item that players carry throughout their run.
 *
 * The map is populated when a journey is selected and placed in the terminal chest.
 * It shows the player their starting instruction and virtual address.
 *
 * Call {@link #giveInitialMap(Player, String)} after assigning a journey.
 * Call {@link #updateMap(Player)} later to add newly-discovered vars (VPN, PFN, etc.).
 */
public class JourneyMapManager {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;

    // One MapView per player so each render is independent
    private final Map<UUID, MapView> playerMapViews = new HashMap<>();

    public JourneyMapManager(JavaPlugin plugin, JourneyTracker tracker) {
        this.plugin  = plugin;
        this.tracker = tracker;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates (or refreshes) the player's journey map item and places it in
     * the chest named by {@code chestConfigKey} (a key under {@code chests.*}
     * in config.yml).
     *
     * Call this immediately after {@code tracker.setJourney(player, ...)} so
     * that the map reflects the freshly initialised vars.
     */
    public void giveInitialMap(Player player, String chestConfigKey) {
        MapView view = getOrCreateView(player);
        rerender(view, buildLines(tracker.getVars(player)));
        placeInChest(buildMapItem(view), chestConfigKey);
    }

    /**
     * Refreshes the map content using the player's current journey vars.
     * Use this to add information that becomes available mid-journey
     * (e.g. after the calculator or page table walk).
     */
    public void updateMap(Player player) {
        MapView view = playerMapViews.get(player.getUniqueId());
        if (view == null) return;
        rerender(view, buildLines(tracker.getVars(player)));
    }

    // ── Content builder ───────────────────────────────────────────────────────

    /**
     * Builds the text lines for the map.
     * Only vars that are pedagogically appropriate to reveal at each stage
     * are shown: instruction and VA are always shown; VPN, offset, PFN, etc.
     * appear only once they are no longer "?" (i.e. have been discovered).
     */
    private List<String> buildLines(Map<String, String> vars) {
        List<String> lines = new ArrayList<>();

        lines.add("= OSCity Journey Map =");
        lines.add(safe(vars.getOrDefault("journey", "Journey")));
        lines.add(safe(vars.getOrDefault("process", "")));
        lines.add("");

        lines.add("Instruction:");
        for (String l : wrap(vars.getOrDefault("instruction", "?"), 20)) {
            lines.add(safe(l));
        }
        lines.add("");

        lines.add("VA: " + safe(vars.getOrDefault("va", "?")));

        // VPN / offset — revealed after calculator room
        String vpn    = vars.getOrDefault("vpn",    "?");
        String offset = vars.getOrDefault("offset", "?");
        if (!"?".equals(vpn))    lines.add("VPN:  " + safe(vpn));
        if (!"?".equals(offset)) lines.add("OFF:  " + safe(offset));

        // PFN — revealed after TLB hit or page table walk
        String pfn = vars.getOrDefault("pfn", "?");
        if (!"?".equals(pfn))    lines.add("PFN:  " + safe(pfn));

        // File-backed page: file name and page index
        String file     = vars.getOrDefault("file",      "?");
        String pageIdx  = vars.getOrDefault("pageIndex", "?");
        if (!"?".equals(file) && !file.isEmpty())    lines.add(safe(cap("File: " + file, 20)));
        if (!"?".equals(pageIdx) && !pageIdx.isEmpty()) lines.add("PgIdx: " + safe(pageIdx));

        // Swap slot (revealed when the PTE is checked)
        String slot = vars.getOrDefault("slot", "?");
        if (!"?".equals(slot) && !slot.isEmpty()) lines.add("Slot: " + safe(slot));

        return lines;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void rerender(MapView view, List<String> lines) {
        view.getRenderers().clear();

        final byte bg     = MapPalette.matchColor(new Color(10, 20, 40));   // dark navy
        final byte accent = MapPalette.matchColor(new Color(30, 80, 150));  // steel blue

        view.addRenderer(new MapRenderer(false) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player player) {
                // Background
                for (int x = 0; x < 128; x++)
                    for (int y = 0; y < 128; y++)
                        canvas.setPixel(x, y, bg);

                // Thin top and bottom accent bars
                for (int x = 0; x < 128; x++) {
                    canvas.setPixel(x, 0,   accent);
                    canvas.setPixel(x, 1,   accent);
                    canvas.setPixel(x, 126, accent);
                    canvas.setPixel(x, 127, accent);
                }

                // Text lines
                int y = 5;
                for (String line : lines) {
                    if (y > 118) break;
                    if (!line.isEmpty()) {
                        canvas.drawText(3, y, MinecraftFont.Font, line);
                    }
                    y += 14;
                }
            }
        });
    }

    // ── Map item ──────────────────────────────────────────────────────────────

    private ItemStack buildMapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.displayName(Component.text("§bOSCity Journey Map"));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Chest placement ───────────────────────────────────────────────────────

    private void placeInChest(ItemStack mapItem, String chestKey) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("chests." + chestKey);
        if (sec == null) {
            plugin.getLogger().warning("[JourneyMap] No config at chests." + chestKey);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();

        if (!(block.getState() instanceof org.bukkit.block.Chest chest)) {
            plugin.getLogger().warning("[JourneyMap] No chest block at chests." + chestKey
                + " (found: " + block.getType() + ")");
            return;
        }

        Inventory inv = chest.getInventory();
        inv.clear();
        inv.setItem(13, mapItem); // slot 13 = centre of a 27-slot chest
        plugin.getLogger().info("[JourneyMap] Journey map placed in chests." + chestKey);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private MapView getOrCreateView(Player player) {
        MapView view = playerMapViews.get(player.getUniqueId());
        if (view == null) {
            view = Bukkit.createMap(player.getWorld());
            view.setScale(MapView.Scale.CLOSEST);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            playerMapViews.put(player.getUniqueId(), view);
        }
        return view;
    }

    /** Wrap {@code text} into lines of at most {@code maxLen} chars. */
    private List<String> wrap(String text, int maxLen) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) { result.add("?"); return result; }
        while (text.length() > maxLen) {
            int cut = text.lastIndexOf(' ', maxLen);
            if (cut <= 0) cut = maxLen;
            result.add(text.substring(0, cut).trim());
            text = text.substring(cut).trim();
        }
        if (!text.isEmpty()) result.add(text);
        return result;
    }

    /** Truncate string to {@code max} characters. */
    private String cap(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : (s != null ? s : "");
    }

    /**
     * Replace characters not supported by MinecraftFont with '?'
     * to avoid IllegalArgumentException from canvas.drawText().
     */
    private String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(MinecraftFont.Font.getChar(c) != null ? c : '?');
        }
        return sb.toString();
    }
}
