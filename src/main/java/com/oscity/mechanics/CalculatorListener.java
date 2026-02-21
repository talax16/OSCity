package com.oscity.mechanics;

import com.oscity.session.JourneyTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;
import org.bukkit.map.MapPalette;
import java.awt.Color;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the Calculator Room mechanic:
 *   1. Player writes a hex/binary number in a Writable Book
 *   2. Places the book in the hopper
 *   3. Plugin reads the book, runs a 5-second "calculation", then shows the result
 *
 * Both visits compute the same result: binary representation → VPN + offset split.
 *
 * Visit 1 (phase: calculator_from_tlb):          instruction signs = hex->binary guide
 * Visit 2 (phase: calculator_from_lazy_loading):  instruction signs = VA decomposition formula
 *
 * Frames can be either wall signs OR item frames (books will be written to item frames).
 */
public class CalculatorListener implements Listener {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;

    private Location hopperLocation;
    private final List<Location> instrFrames = new ArrayList<>();
    private final List<Location> calcFrames  = new ArrayList<>();
    private int pageOffsetBits = 4; // default: 4 bits offset (8-bit VA)

    /** Players currently inside a 5-second calculation — suppress duplicate triggers. */
    private final Map<UUID, Boolean> calculating = new HashMap<>();

    /** Cached MapView per frame location so we reuse the same map ID across updates. */
    private final Map<Location, MapView> frameMapViews = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CalculatorListener(JavaPlugin plugin, JourneyTracker tracker) {
        this.plugin  = plugin;
        this.tracker = tracker;
        loadConfig();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("CalculatorListener registered.");
    }

    // ── Config loading ────────────────────────────────────────────────────────

    private void loadConfig() {
        ConfigurationSection hopperSec = plugin.getConfig().getConfigurationSection("hopper");
        if (hopperSec != null) {
            World world = Bukkit.getWorld(hopperSec.getString("world", "OSCityWorld"));
            hopperLocation = new Location(world,
                hopperSec.getInt("x"), hopperSec.getInt("y"), hopperSec.getInt("z"));
        }

        ConfigurationSection frames = plugin.getConfig().getConfigurationSection("signs.calculatorRoom");
        if (frames != null) {
            for (int i = 1; i <= 6; i++) {
                ConfigurationSection instr = frames.getConfigurationSection("instructionsFrame" + i);
                if (instr != null) instrFrames.add(locFrom(instr));
                ConfigurationSection calc = frames.getConfigurationSection("calculationFrame" + i);
                if (calc != null) calcFrames.add(locFrom(calc));
            }
        }

        pageOffsetBits = plugin.getConfig().getInt("calculator.pageOffsetBits", 4);
        plugin.getLogger().info("CalculatorListener: " + instrFrames.size()
            + " instruction frames, " + calcFrames.size() + " calculation frames, "
            + "pageOffsetBits=" + pageOffsetBits);
    }

    private Location locFrom(ConfigurationSection sec) {
        World world = Bukkit.getWorld(sec.getString("world", "OSCityWorld"));
        return new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
    }

    // ── Called by RoomChangeListener on room entry ────────────────────────────

    /**
     * Update instruction signs and reset calculation display.
     * phase should be "calculator_from_tlb" or "calculator_from_lazy_loading".
     */
    public void onCalculatorRoomEntered(Player player, String phase) {
        plugin.getLogger().info("[FrameDebug] Room entered. instrFrames=" + instrFrames.size()
            + " calcFrames=" + calcFrames.size() + " phase=" + phase);
        for (int i = 0; i < instrFrames.size(); i++) {
            Location l = instrFrames.get(i);
            plugin.getLogger().info("[FrameDebug] instrFrame" + (i+1) + ": ("
                + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")");
        }
        for (int i = 0; i < calcFrames.size(); i++) {
            Location l = calcFrames.get(i);
            plugin.getLogger().info("[FrameDebug] calcFrame" + (i+1) + ": ("
                + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")");
        }
        updateInstructionFrames(phase);
        setCalcAwaiting();
        calculating.remove(player.getUniqueId());
    }

    // ── Hopper detection ──────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (hopperLocation == null) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!isHopperInventory(event.getView().getTopInventory())) return;

        Player player = (Player) event.getWhoClicked();
        // Delay 1 tick so the item has actually moved into the hopper
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkHopper(player), 1L);
    }

    private boolean isHopperInventory(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof org.bukkit.block.Hopper)) return false;
        Location loc = ((org.bukkit.block.Hopper) holder).getLocation();
        return hopperLocation != null
            && loc.getBlockX() == hopperLocation.getBlockX()
            && loc.getBlockY() == hopperLocation.getBlockY()
            && loc.getBlockZ() == hopperLocation.getBlockZ();
    }

    private void checkHopper(Player player) {
        if (calculating.getOrDefault(player.getUniqueId(), false)) return;

        Block block = hopperLocation.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Hopper)) return;
        org.bukkit.block.Hopper hopper = (org.bukkit.block.Hopper) block.getState();

        ItemStack book = null;
        int bookSlot = -1;
        for (int i = 0; i < hopper.getInventory().getSize(); i++) {
            ItemStack item = hopper.getInventory().getItem(i);
            if (item != null && item.getType() == Material.WRITABLE_BOOK) {
                book = item; bookSlot = i; break;
            }
        }
        if (book == null) return;

        BookMeta meta = (BookMeta) book.getItemMeta();
        List<Component> pages = meta != null ? meta.pages() : java.util.Collections.emptyList();
        if (pages.isEmpty()) {
            player.sendMessage("§c[Calculator] First page is empty. Write a number.");
            return;
        }
        String pageText = PlainTextComponentSerializer.plainText().serialize(pages.get(0)).trim();
        String input    = pageText.split("\n")[0].trim();
        if (input.isEmpty()) {
            player.sendMessage("§c[Calculator] First page is empty. Write a number.");
            return;
        }

        // Remove book from hopper, return it to the player
        hopper.getInventory().setItem(bookSlot, null);
        player.getInventory().addItem(book);

        String phase = tracker.getPhase(player);
        startCalculation(player, input, phase);
    }

    // ── Calculation sequence ──────────────────────────────────────────────────

    private void startCalculation(Player player, String input, String phase) {
        calculating.put(player.getUniqueId(), true);
        setCalcCalculating();
        player.sendMessage("§6[Calculator] §eProcessing: §f" + input + "§e...");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            calculating.remove(player.getUniqueId());
            try {
                long value   = parseInput(input);
                showResult(input, value);
                String summary = buildChatSummary(value);
                player.sendMessage("§6[Calculator] §aResult: §f" + summary
                    + "§a — added to your log.");
            } catch (NumberFormatException e) {
                setCalcError(input);
                player.sendMessage("§c[Calculator] Could not parse '" + input
                    + "'. Use hex (0xFF), binary (0b1010), or decimal.");
            }
        }, 100L); // 5 seconds = 100 ticks
    }

    // ── Computation ───────────────────────────────────────────────────────────

    private long parseInput(String raw) throws NumberFormatException {
        String s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
        if (s.startsWith("0b") || s.startsWith("0B")) return Long.parseLong(s.substring(2), 2);
        if (s.matches("[01]+") && s.length() >= 4)    return Long.parseLong(s, 2);
        if (s.matches("[0-9A-Fa-f]+"))                return Long.parseLong(s, 16);
        return Long.parseLong(s);
    }

    /** Both visits: binary, VPN (upper bits), offset (lower bits). */
    private String buildChatSummary(long value) {
        String binary = formatNibbles(value, pageOffsetBits * 2);
        long vpn = value >> pageOffsetBits;
        long off = value & ((1L << pageOffsetBits) - 1);
        return "Binary=" + binary
            + ", VPN=" + vpn + " (0x" + Long.toHexString(vpn).toUpperCase() + ")"
            + ", Offset=" + off + " (0x" + Long.toHexString(off).toUpperCase() + ")";
    }

    // ── Instruction signs ─────────────────────────────────────────────────────

    private void updateInstructionFrames(String phase) {
        if (instrFrames.size() < 6) return;
        if ("calculator_from_tlb".equals(phase)) {
            // Visit 1: hex → binary guide; output shows binary split into VPN + offset
            setFrame(instrFrames.get(0), "= HOW TO USE =", " CALCULATOR ", "", "HEX->VPN+OFFSET");
            setFrame(instrFrames.get(1), "   STEP 1:   ", "Write your hex", "VA in the book", "from chest (L)");
            setFrame(instrFrames.get(2), "   STEP 2:   ", "Place the book", "in the hopper", "");
            setFrame(instrFrames.get(3), "The result", "will be shown", "over the hopper", "");
            setFrame(instrFrames.get(4), "First 4 bits:", "= your VPN", "", "");
            setFrame(instrFrames.get(5), "Last 4 bits:", "= your offset", "", "");
        } else {
            // Visit 2: Page Index formula + usage reminder
            setFrame(instrFrames.get(0), "= HOW TO USE =", " PAGE INDEX ", "", "CALCULATOR");
            setFrame(instrFrames.get(1), "   STEP 1:   ", "Write hex OR", "binary", "in a book");
            setFrame(instrFrames.get(2), "   STEP 2:   ", "Place book in", "the hopper", "");
            setFrame(instrFrames.get(3), " IMPORTANT!  ", "Use ONE form:", " all HEX  OR", " all BINARY");
            setFrame(instrFrames.get(4), "  FORMULA:   ", "PAGE INDEX" , "= OFFSET / PAGE SIZE","");
            setFrame(instrFrames.get(5), "   TIP:      ", "Convert hex->", "binary first,", "then calculate");
        }
    }

    // ── Calculation display states ────────────────────────────────────────────

    private void setCalcAwaiting() {
        if (calcFrames.size() < 6) return;
        // Layout: 3 wide × 2 tall
        //  [STEP 1 guide] [AWAITING  ←large] [STEP 2 guide]
        //  [STEP 3 guide] [INPUT...  ←large] [result note ]
        setFrameCentered(calcFrames.get(0), "[ STEP 1 ]", "Get the book", "from the chest", "on your left");
        setFrameLarge(   calcFrames.get(1), "AWAITING", "", "", "");
        setFrameCentered(calcFrames.get(2), "[ STEP 2 ]", "Write your", "hex VA in", "the book");
        setFrameCentered(calcFrames.get(3), "[ STEP 3 ]", "Place book", "in hopper", "above this");
        setFrameLarge(   calcFrames.get(4), "INPUT...", "", "", "");
        setFrameCentered(calcFrames.get(5), "[ RESULT ]", "Will appear", "on this wall", "");
    }

    private void setCalcCalculating() {
        if (calcFrames.size() < 6) return;
        clearFrame(calcFrames.get(0));
        setFrameLarge(   calcFrames.get(1), "WORKING", "", "", "");
        clearFrame(calcFrames.get(2));
        setFrameCentered(calcFrames.get(3), "", "Please wait", "5 seconds...", "");
        setFrameLarge(   calcFrames.get(4), "WAIT...", "", "", "");
        clearFrame(calcFrames.get(5));
    }

    private void setCalcError(String input) {
        if (calcFrames.size() < 6) return;
        setFrame(calcFrames.get(0), "=== ERROR ===", "Bad input:",
            shorten(input, 13), "Try again");
        for (int i = 1; i < calcFrames.size(); i++) clearFrame(calcFrames.get(i));
    }

    /**
     * Both visits: show full binary, then VPN (upper bits) and offset (lower bits).
     * The instruction frames differ between visits, but the result is the same.
     */
    private void showResult(String input, long value) {
        if (calcFrames.size() < 6) return;

        int totalBits = pageOffsetBits * 2; // e.g. 8-bit VA with 4-bit VPN + 4-bit offset
        String binary = formatNibbles(value, totalBits);
        long vpn = value >> pageOffsetBits;
        long off = value & ((1L << pageOffsetBits) - 1);
        String vpnBin = formatNibbles(vpn, pageOffsetBits);
        String offBin = formatNibbles(off, pageOffsetBits);
        String vpnHex = "0x" + Long.toHexString(vpn).toUpperCase();
        String offHex = "0x" + Long.toHexString(off).toUpperCase();

        setFrame(calcFrames.get(0), "== RESULT ==", "VA: " + shorten(input, 11), "", "");
        setFrame(calcFrames.get(1), "Binary:", binary, "", "");
        setFrame(calcFrames.get(2), "VPN: " + vpnBin, "= " + vpn + "  " + vpnHex, "", "");
        setFrame(calcFrames.get(3), "Offset: " + offBin, "= " + off + "  " + offHex, "", "");
        setFrame(calcFrames.get(4), "Added to log!", "", "", "");
        clearFrame(calcFrames.get(5));
    }

    // ── Frame helper — supports both wall signs and item frames ───────────────

    /** Centred, normal-scale text. */
    private void setFrameCentered(Location loc, String l1, String l2, String l3, String l4) {
        setFrame(loc, l1, l2, l3, l4, true, 1);
    }

    /** Centred, 2× scaled text — for prominent status frames. */
    private void setFrameLarge(Location loc, String l1, String l2, String l3, String l4) {
        setFrame(loc, l1, l2, l3, l4, true, 2);
    }

    private void setFrame(Location loc, String l1, String l2, String l3, String l4) {
        setFrame(loc, l1, l2, l3, l4, false, 1);
    }

    private void setFrame(Location loc, String l1, String l2, String l3, String l4, boolean centered) {
        setFrame(loc, l1, l2, l3, l4, centered, 1);
    }

    /**
     * Write 4 lines to a frame. Tries wall sign first; falls back to item frame map.
     * centered=true centres the text block; scale=2 renders at 2× pixel size.
     */
    private void setFrame(Location loc, String l1, String l2, String l3, String l4,
                          boolean centered, int scale) {
        // 1. Try sign block (no scaling support for signs)
        Block block = loc.getBlock();
        if (block.getState() instanceof Sign) {
            Sign sign = (Sign) block.getState();
            sign.getSide(Side.FRONT).line(0, Component.text(l1, NamedTextColor.BLACK));
            sign.getSide(Side.FRONT).line(1, Component.text(l2, NamedTextColor.BLACK));
            sign.getSide(Side.FRONT).line(2, Component.text(l3, NamedTextColor.BLACK));
            sign.getSide(Side.FRONT).line(3, Component.text(l4, NamedTextColor.BLACK));
            sign.update(true);
            return;
        }

        // 2. Try item frame entity — render text onto a map
        ItemFrame frame = findItemFrameAt(loc);
        if (frame != null) {
            renderTextToFrame(frame, loc, l1, l2, l3, l4, centered, scale);
            return;
        }

        plugin.getLogger().warning("CalculatorListener: no sign or item frame at "
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
            + " (block=" + block.getType() + ")");
    }

    private ItemFrame findItemFrameAt(Location loc) {
        Location center = loc.clone().add(0.5, 0.5, 0.5);

        // Log all nearby entities to help debug missing frames
        java.util.Collection<Entity> nearby = loc.getWorld().getNearbyEntities(center, 2.0, 2.0, 2.0);
        if (nearby.isEmpty()) {
            plugin.getLogger().info("[FrameDebug] No entities within 2 blocks of ("
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")");
        } else {
            for (Entity entity : nearby) {
                Location el = entity.getLocation();
                double dist = el.distance(center);
                plugin.getLogger().info("[FrameDebug] Near (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "): "
                    + entity.getType() + " at (" + String.format("%.2f", el.getX()) + ","
                    + String.format("%.2f", el.getY()) + "," + String.format("%.2f", el.getZ()) + ")"
                    + " dist=" + String.format("%.2f", dist)
                    + (entity instanceof ItemFrame ? " [ITEM FRAME]" : ""));
            }
        }

        ItemFrame closest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity entity : nearby) {
            if (entity instanceof ItemFrame) {
                double dist = entity.getLocation().distanceSquared(center);
                if (dist < minDist) {
                    minDist = dist;
                    closest = (ItemFrame) entity;
                }
            }
        }
        if (closest != null) {
            plugin.getLogger().info("[FrameDebug] -> Using frame at ("
                + String.format("%.2f", closest.getLocation().getX()) + ","
                + String.format("%.2f", closest.getLocation().getY()) + ","
                + String.format("%.2f", closest.getLocation().getZ()) + ")");
        } else {
            plugin.getLogger().warning("[FrameDebug] -> No item frame found near ("
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")");
        }
        return closest;
    }

    /**
     * Render up to 4 lines of text onto a map item placed in the given item frame.
     * Reuses the same MapView across updates to keep the same map ID.
     * scale=1 → normal text; scale=2 → 2× enlarged pixels for big status displays.
     */
    @SuppressWarnings("deprecation")
    private void renderTextToFrame(ItemFrame frame, Location key,
                                   String l1, String l2, String l3, String l4,
                                   boolean centered, int scale) {
        MapView view = frameMapViews.get(key);
        if (view == null) {
            view = Bukkit.createMap(frame.getWorld());
            view.setScale(MapView.Scale.CLOSEST);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            frameMapViews.put(key, view);
        }

        view.getRenderers().clear();
        final String tl1 = l1, tl2 = l2, tl3 = l3, tl4 = l4;
        final boolean tc = centered;
        final int ts = scale;
        final byte bg = MapPalette.matchColor(new Color(30, 30, 50));   // dark navy
        final byte fg = MapPalette.matchColor(new Color(255, 255, 255)); // white
        view.addRenderer(new MapRenderer(false) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player player) {
                // Dark background
                for (int px = 0; px < 128; px++)
                    for (int py = 0; py < 128; py++)
                        canvas.setPixel(px, py, bg);

                String[] lines = {tl1, tl2, tl3, tl4};
                int nonEmpty = 0;
                for (String l : lines) if (!l.trim().isEmpty()) nonEmpty++;

                if (ts > 1) {
                    // 2× scaled pixel rendering for large centred text
                    int lineSpacing = 8 * ts + 2;
                    int y = tc ? Math.max(4, (128 - nonEmpty * lineSpacing) / 2) : 4;
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            int x = tc ? Math.max(2, (128 - textWidth(line) * ts) / 2) : 4;
                            drawScaledText(canvas, x, y, line, ts, fg);
                            y += lineSpacing;
                        }
                    }
                } else {
                    // Normal-size text
                    final int lineSpacing = tc ? 18 : 14;
                    int y = tc ? Math.max(8, (128 - nonEmpty * lineSpacing) / 2) : 8;
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            int x = tc ? Math.max(2, (128 - textWidth(line)) / 2) : 4;
                            canvas.drawText(x, y, MinecraftFont.Font, line);
                            y += lineSpacing;
                        }
                    }
                }
            }
        });

        // Put the map item into the frame
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);
        frame.setItem(mapItem, false);
    }

    private void clearFrame(Location loc) { setFrame(loc, "", "", "", ""); }

    // ── Text formatting helpers ───────────────────────────────────────────────

    /**
     * Format a number as binary, grouped in nibbles (e.g. "1010 1011").
     * Padded to at least minBits bits (rounded up to multiple of 4).
     */
    private String formatNibbles(long value, int minBits) {
        int rawLen = Long.toBinaryString(value).length();
        int padded = ((Math.max(rawLen, minBits) + 3) / 4) * 4;
        String bin = String.format("%" + padded + "s", Long.toBinaryString(value)).replace(' ', '0');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bin.length(); i += 4) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(bin, i, Math.min(i + 4, bin.length()));
        }
        return sb.toString();
    }

    private String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Returns the pixel width of a string rendered with MinecraftFont. */
    private int textWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        for (char c : s.toCharArray()) {
            MapFont.CharacterSprite cs = MinecraftFont.Font.getChar(c);
            if (cs != null) w += cs.getWidth() + 1;
        }
        return Math.max(0, w - 1);
    }

    /**
     * Draws text onto a MapCanvas at (startX, startY) with each font pixel
     * blown up to a scale×scale block — giving visually larger text.
     */
    @SuppressWarnings("deprecation")
    private void drawScaledText(MapCanvas canvas, int startX, int startY,
                                String text, int scale, byte color) {
        int x = startX;
        for (char c : text.toCharArray()) {
            MapFont.CharacterSprite cs = MinecraftFont.Font.getChar(c);
            if (cs == null) { x += 4 * scale; continue; }
            for (int row = 0; row < cs.getHeight(); row++) {
                for (int col = 0; col < cs.getWidth(); col++) {
                    if (cs.get(row, col)) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int px = x + col * scale + sx;
                                int py = startY + row * scale + sy;
                                if (px >= 0 && px < 128 && py >= 0 && py < 128)
                                    canvas.setPixel(px, py, color);
                            }
                        }
                    }
                }
            }
            x += (cs.getWidth() + 1) * scale;
        }
    }
}
