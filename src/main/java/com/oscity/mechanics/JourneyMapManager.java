package com.oscity.mechanics;

import com.oscity.mode.PlayerMode;
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
        boolean isLearner = tracker.getMode(player) == PlayerMode.LEARNER;
        MapView view = getOrCreateView(player);
        rerender(view, buildLines(tracker.getVars(player), false, !isLearner));
        placeInChest(buildMapItem(view), chestConfigKey);
    }

    /**
     * Refreshes the map content using the player's current journey vars.
     * Use this to add information that becomes available mid-journey
     * (e.g. after the calculator or page table walk).
     */
    public void updateMap(Player player) {
        boolean isLearner = tracker.getMode(player) == PlayerMode.LEARNER;
        MapView view = playerMapViews.get(player.getUniqueId());
        if (view == null) return;
        rerender(view, buildLines(tracker.getVars(player), true, !isLearner));
    }

    /**
     * Updates the map after calculator - shows VPN and offset only (no PFN yet).
     * PFN is revealed later from TLB hit or page table walk.
     */
    public void updateMapAfterCalculator(Player player) {
        boolean isLearner = tracker.getMode(player) == PlayerMode.LEARNER;
        MapView view = playerMapViews.get(player.getUniqueId());
        if (view == null) return;
        rerender(view, buildLinesAfterCalculator(tracker.getVars(player), !isLearner));
    }

    /**
     * Updates the map after retrieving PTE from page table library.
     * Adds PFN and other PTE-discovered information.
     */
    public void updateMapAfterPTE(Player player) {
        updateMap(player);
    }

    // ── Content builder ───────────────────────────────────────────────────────

    /**
     * Builds the text lines for the map.
     *
     * @param vars the player's current journey vars
     * @param showDiscoveries if true, show VPN/offset/PFN/file/slot (when not "?")
     * @param showJourneyName if true, show the journey name on the map
     */
    private List<String> buildLines(Map<String, String> vars, boolean showDiscoveries, boolean showJourneyName) {
        List<String> lines = new ArrayList<>();

        lines.add("= OSCity Journey Map =");
        if (showJourneyName) {
            lines.add(safe(vars.getOrDefault("journey", "Journey")));
        }
        lines.add(safe(vars.getOrDefault("process", "")));
        lines.add("");

        // Instruction: split into operation and file+address
        // Format: "load treasure_map.bin 0x8E" → "Instruction: load" + "treasure_map.bin 0x8E"
        String instruction = vars.getOrDefault("instruction", "?");
        if (!"?".equals(instruction) && instruction != null && !instruction.isEmpty()) {
            // Split on first space to separate operation from file+address
            int firstSpace = instruction.indexOf(' ');
            if (firstSpace > 0) {
                String operation = instruction.substring(0, firstSpace);
                String fileAndAddr = instruction.substring(firstSpace + 1);
                lines.add("Instruction: " + safe(operation));
                lines.add(safe(fileAndAddr));
            } else {
                lines.add("Instruction: " + safe(instruction));
            }
        } else {
            lines.add("Instruction: ?");
        }
        lines.add("");

        // VA in hex only
        String vaHex = vars.getOrDefault("va", "?");
        lines.add("VA: " + safe(vaHex));

        // VPN / offset — revealed after calculator room (only if showDiscoveries)
        if (showDiscoveries) {
            String vpnBin = vars.getOrDefault("vpn", "?");
            String vpnHex = vars.getOrDefault("vpnHex", "?");
            String offsetBin = vars.getOrDefault("offset", "?");
            String offsetHex = vars.getOrDefault("offsetHex", "?");

            if (!"?".equals(vpnBin)) {
                lines.add("VPN: " + safe(vpnBin) + " = " + safe(vpnHex));
            }
            if (!"?".equals(offsetBin)) {
                lines.add("OFFSET: " + safe(offsetBin) + " = " + safe(offsetHex));
            }

            // Page size for Lazy Loading (revealed after entering Lazy Loading room)
            String pageSize = vars.getOrDefault("pageSize", "?");
            if (!"?".equals(pageSize) && !pageSize.isEmpty()) {
                lines.add("Page Size: " + safe(pageSize));
            }

            // Page index for Lazy Loading (revealed after second calculator visit)
            String pageIdx  = vars.getOrDefault("pageIndex", "?");
            if (!"?".equals(pageIdx) && !pageIdx.isEmpty()) {
                lines.add("Page_index: " + safe(pageIdx));
            }

        }

        return lines;
    }

    /**
     * Builds map lines after calculator - shows VPN and offset only (no PFN).
     * PFN is revealed later from TLB hit or page table walk.
     */
    private List<String> buildLinesAfterCalculator(Map<String, String> vars, boolean showJourneyName) {
        List<String> lines = new ArrayList<>();

        lines.add("= OSCity Journey Map =");
        if (showJourneyName) {
            lines.add(safe(vars.getOrDefault("journey", "Journey")));
        }
        lines.add(safe(vars.getOrDefault("process", "")));
        lines.add("");

        // Instruction: split into operation and file+address
        // Format: "load treasure_map.bin 0x8E" → "Instruction: load" + "treasure_map.bin 0x8E"
        String instruction = vars.getOrDefault("instruction", "?");
        if (!"?".equals(instruction) && instruction != null && !instruction.isEmpty()) {
            // Split on first space to separate operation from file+address
            int firstSpace = instruction.indexOf(' ');
            if (firstSpace > 0) {
                String operation = instruction.substring(0, firstSpace);
                String fileAndAddr = instruction.substring(firstSpace + 1);
                lines.add("Instruction: " + safe(operation));
                lines.add(safe(fileAndAddr));
            } else {
                lines.add("Instruction: " + safe(instruction));
            }
        } else {
            lines.add("Instruction: ?");
        }
        lines.add("");

        // VA in hex only
        String vaHex = vars.getOrDefault("va", "?");
        lines.add("VA: " + safe(vaHex));

        // VPN and offset only - NO PFN (comes from TLB or page table)
        String vpnBin = vars.getOrDefault("vpn", "?");
        String vpnHex = vars.getOrDefault("vpnHex", "?");
        String offsetBin = vars.getOrDefault("offset", "?");
        String offsetHex = vars.getOrDefault("offsetHex", "?");

        if (!"?".equals(vpnBin)) {
            lines.add("VPN: " + safe(vpnBin) + " = " + safe(vpnHex));
        }
        if (!"?".equals(offsetBin)) {
            lines.add("OFFSET: " + safe(offsetBin) + " = " + safe(offsetHex));
        }

        // Page size for Lazy Loading (revealed after entering Lazy Loading room)
        String pageSize = vars.getOrDefault("pageSize", "?");
        if (!"?".equals(pageSize) && !pageSize.isEmpty()) {
            lines.add("Page Size: " + safe(pageSize));
        }

        // Page index for Lazy Loading (revealed after second calculator visit)
        String pageIdx  = vars.getOrDefault("pageIndex", "?");
        if (!"?".equals(pageIdx) && !pageIdx.isEmpty()) {
            lines.add("Page_index: " + safe(pageIdx));
        }

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

                // Text lines - optimized spacing to fit more content
                int y = 2;
                for (String line : lines) {
                    if (y > 124) break;  // Extended to use full height
                    if (!line.isEmpty()) {
                        canvas.drawText(2, y, MinecraftFont.Font, line);
                    }
                    y += 10;  // Reduced from 11 to fit more lines
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
