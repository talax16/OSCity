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
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Populates the 9 TLB entry slots (signs + chest maps) when a player enters the TLB Room.
 *
 * Each slot has:
 *  - A wall sign showing the VPN stored in that slot  (signs.tlb.vpnN)
 *  - A chest containing a rendered FILLED_MAP showing "VPN: 0xN → PFN: 0xM"  (chests.tlb.chestN)
 *
 * For TLB-hit journeys (Journey.LUCKY): one slot contains the player's real VPN→PFN pair.
 * For all other journeys (TLB miss): all 9 slots show different VPNs that do not match
 * the player's, so the player correctly finds no match.
 *
 * Call {@link #populate(Player)} from RoomChangeListener when the player enters the TLB Room.
 */
public class TLBRoomManager {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;

    /** All 16 possible 4-bit VPN values in hex. */
    private static final String[] ALL_VPNS = {
        "0x0","0x1","0x2","0x3","0x4","0x5","0x6","0x7",
        "0x8","0x9","0xA","0xB","0xC","0xD","0xE","0xF"
    };

    public TLBRoomManager(JavaPlugin plugin, JourneyTracker tracker) {
        this.plugin  = plugin;
        this.tracker = tracker;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fills all 9 TLB signs and chest-maps for the given player's current journey.
     * Safe to call every time the player enters the TLB Room.
     */
    public void populate(Player player) {
        plugin.getLogger().info("[TLBRoom] populate() called for " + player.getName());
        Journey journey = tracker.getJourney(player);
        if (journey == null) {
            plugin.getLogger().warning("[TLBRoom] No journey for " + player.getName());
            return;
        }

        String playerVpn = tracker.getVar(player, "vpnHex"); // e.g. "0x2"
        String playerPfn = tracker.getVar(player, "pfn");    // e.g. "0x3"
        boolean isHit    = journey.isTlbHit;

        plugin.getLogger().info("[TLBRoom] Journey=" + journey.name() + " isTlbHit=" + isHit
            + " playerVpn=" + playerVpn + " playerPfn=" + playerPfn);

        // For a hit, one specific slot shows the player's real VPN
        int hitSlot = isHit ? slotForVpn(playerVpn) : -1;
        plugin.getLogger().info("[TLBRoom] hitSlot=" + hitSlot);

        // Build a list of 9 fake VPNs that exclude the player's VPN
        List<String> fakeVpns = buildFakeVpnPool(player, playerVpn, 9);
        int fakeIdx = 0;

        for (int i = 1; i <= 9; i++) {
            String entryVpn, entryPfn;
            if (i == hitSlot) {
                entryVpn = playerVpn;
                entryPfn = playerPfn;
            } else {
                entryVpn = fakeVpns.get(fakeIdx++);
                entryPfn = fakePfnFor(entryVpn);
            }
            plugin.getLogger().info("[TLBRoom] Slot " + i + ": VPN=" + entryVpn + " PFN=" + entryPfn);
            updateTLBSign(i, entryVpn);
            placeTLBChestMap(i, entryVpn, entryPfn, player);
        }
    }

    // ── Slot / VPN helpers ────────────────────────────────────────────────────

    /** Map a VPN hex string deterministically to a slot number 1–9. */
    private int slotForVpn(String vpnHex) {
        try {
            int val = Integer.parseInt(vpnHex.replace("0x", "").replace("0X", ""), 16);
            return (val % 9) + 1;
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    /**
     * Build a shuffled list of VPNs that exclude {@code excludeVpn}.
     * Uses the player's UUID as a seed so the order is reproducible per-player
     * but different across players.
     */
    private List<String> buildFakeVpnPool(Player player, String excludeVpn, int count) {
        List<String> pool = new ArrayList<>();
        for (String v : ALL_VPNS) {
            if (!v.equalsIgnoreCase(excludeVpn)) pool.add(v);
        }
        long seed = player.getUniqueId().getLeastSignificantBits();
        Collections.shuffle(pool, new Random(seed));
        return pool.subList(0, Math.min(count, pool.size()));
    }

    /** Deterministic fake PFN derived from a VPN string (always different from the VPN). */
    private String fakePfnFor(String vpnHex) {
        try {
            int val = Integer.parseInt(vpnHex.replace("0x", "").replace("0X", ""), 16);
            int pfn = (val + 5) % 16;
            return "0x" + Integer.toHexString(pfn).toUpperCase();
        } catch (NumberFormatException e) {
            return "0x1";
        }
    }

    // ── Sign update ───────────────────────────────────────────────────────────

    private void updateTLBSign(int slotNum, String vpnHex) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("signs.tlb.vpn" + slotNum);
        if (sec == null) {
            plugin.getLogger().warning("[TLBRoom] No config for signs.tlb.vpn" + slotNum);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();

        plugin.getLogger().info("[TLBRoom] updateTLBSign slot=" + slotNum + " at ("
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ") block=" + block.getType());

        if (!(block.getState() instanceof Sign sign)) {
            plugin.getLogger().warning("[TLBRoom] No sign at signs.tlb.vpn" + slotNum
                + " (" + block.getType() + ")");
            return;
        }

        String vpnBin = hexToBinary(vpnHex);
        sign.getSide(Side.FRONT).line(0, Component.text("VPN: " + vpnBin));
        sign.getSide(Side.FRONT).line(1, Component.text(""));
        sign.getSide(Side.FRONT).line(2, Component.text(""));
        sign.getSide(Side.FRONT).line(3, Component.text(""));
        sign.update(true);
    }

    /** Convert hex string (e.g. "0x2") to 4-bit binary string. */
    private String hexToBinary(String vpnHex) {
        try {
            int val = Integer.parseInt(vpnHex.replace("0x", "").replace("0X", ""), 16);
            return String.format("%4s", Integer.toBinaryString(val)).replace(' ', '0');
        } catch (NumberFormatException e) {
            return "????";
        }
    }

    // ── Chest map placement ───────────────────────────────────────────────────

    private void placeTLBChestMap(int slotNum, String vpnHex, String pfnHex, Player player) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("chests.tlb.chest" + slotNum);
        if (sec == null) {
            plugin.getLogger().warning("[TLBRoom] No config for chests.tlb.chest" + slotNum);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        Block block = loc.getBlock();

        plugin.getLogger().info("[TLBRoom] placeTLBChestMap slot=" + slotNum + " at ("
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ") block=" + block.getType());

        if (!(block.getState() instanceof Chest chest)) {
            plugin.getLogger().warning("[TLBRoom] No chest at chests.tlb.chest" + slotNum
                + " (" + block.getType() + ")");
            return;
        }

        Inventory inv = chest.getInventory();
        inv.clear();
        inv.setItem(13, buildTLBEntryMap(slotNum, vpnHex, pfnHex, player));
    }

    // ── Map item rendering ────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private ItemStack buildTLBEntryMap(int slotNum, String vpnHex, String pfnHex, Player player) {
        MapView view = Bukkit.createMap(player.getWorld());
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.getRenderers().clear();

        final byte bg     = MapPalette.matchColor(new Color(10, 20, 40));
        final byte accent = MapPalette.matchColor(new Color(30, 80, 150));
        final String vpn  = safe(vpnHex);
        final String pfn  = safe(pfnHex);

        view.addRenderer(new MapRenderer(false) {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                // Background
                for (int x = 0; x < 128; x++)
                    for (int y = 0; y < 128; y++)
                        canvas.setPixel(x, y, bg);

                // Accent bars
                for (int x = 0; x < 128; x++) {
                    canvas.setPixel(x, 0,   accent);
                    canvas.setPixel(x, 1,   accent);
                    canvas.setPixel(x, 126, accent);
                    canvas.setPixel(x, 127, accent);
                }

                // Content
                canvas.drawText(3,  20, MinecraftFont.Font, "= TLB Entry =");
                canvas.drawText(3,  46, MinecraftFont.Font, "VPN: " + vpn);
                canvas.drawText(3,  64, MinecraftFont.Font, "PFN: " + pfn);
                canvas.drawText(3,  82, MinecraftFont.Font, vpn + " -> " + pfn);
            }
        });

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.displayName(Component.text("§bTLB Entry " + slotNum));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Replace characters unsupported by MinecraftFont with '?'. */
    private String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(MinecraftFont.Font.getChar(c) != null ? c : '?');
        }
        return sb.toString();
    }
}
