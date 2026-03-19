package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.content.QuestionBank;
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
import java.util.logging.Logger;

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

    private static final Logger log = Logger.getLogger("OSCity");

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;
    private final JourneyMapManager journeyMapManager;
    private final QuestionBank questionBank;
    private final DialogueManager dialogueManager;

    private Location hopperLocation;
    private final List<Location> instrFrames = new ArrayList<>();
    private final List<Location> calcFrames  = new ArrayList<>();
    private int pageOffsetBits = 4; // default: 4 bits offset (8-bit VA)

    /** Players currently inside a 5-second calculation — suppress duplicate triggers. */
    private final Map<UUID, Boolean> calculating = new HashMap<>();

    /** Players who have completed the calculator calculation (used hopper or skip). */
    private final Map<UUID, Boolean> hasCalculated = new HashMap<>();

    /** Players awaiting a calc verification question; value = question path. */
    private final Map<UUID, String> pendingCalcVerify = new HashMap<>();

    /** Players who pressed skip (vs used the hopper) — determines post-quiz dialogue. */
    private final Map<UUID, Boolean> wasSkipped = new HashMap<>();


    /** Cached MapView per frame location so we reuse the same map ID across updates. */
    private final Map<Location, MapView> frameMapViews = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CalculatorListener(JavaPlugin plugin, JourneyTracker tracker,
                              JourneyMapManager journeyMapManager, QuestionBank questionBank,
                              DialogueManager dialogueManager) {
        this.plugin          = plugin;
        this.tracker         = tracker;
        this.journeyMapManager = journeyMapManager;
        this.questionBank    = questionBank;
        this.dialogueManager = dialogueManager;
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
        log.info("[Calc] " + player.getName() + " entered Calculator Room | phase=" + phase
            + " | va=" + tracker.getVar(player, "va"));
        updateInstructionFrames(phase);
        setCalcAwaiting();
        calculating.remove(player.getUniqueId());
        hasCalculated.remove(player.getUniqueId());
        pendingCalcVerify.remove(player.getUniqueId());
        wasSkipped.remove(player.getUniqueId());
    }

    /** Called at journey start to remove any book left over from a previous run. */
    public void clearHopper() {
        if (hopperLocation == null) return;
        Block block = hopperLocation.getBlock();
        if (block.getState() instanceof org.bukkit.block.Hopper hopper) {
            hopper.getInventory().clear();
        }
    }

    /**
     * Check if player has completed the calculator calculation.
     */
    public boolean hasPlayerCalculated(Player player) {
        return hasCalculated.getOrDefault(player.getUniqueId(), false);
    }

    /**
     * Check if player has a pending calculator quiz waiting for an answer.
     */
    public boolean hasPendingCalcVerify(Player player) {
        return pendingCalcVerify.containsKey(player.getUniqueId());
    }

    /**
     * Skip button: use the journey's VA directly, show result immediately (no 5s wait).
     * Called by ChoiceButtonHandler when the player presses the skipCalc button.
     */
    public void skipCalculation(Player player) {
        if (calculating.getOrDefault(player.getUniqueId(), false)) return;
        String va = tracker.getVar(player, "va");
        if ("?".equals(va) || va.isEmpty()) {
            player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("errors.calculator.no_va"));
            return;
        }
        String phase = tracker.getPhase(player);
        boolean isPageIndex = "calculator_from_lazy_loading".equals(phase);
        log.info("[Calc] " + player.getName() + " SKIP | phase=" + phase
            + " | isPageIndex=" + isPageIndex + " | va=" + va);
        try {
            long value = parseInput(va);
            log.info("[Calc] Skip result | value=" + value
                + (isPageIndex ? " (pageIndex)" : " (vpn=" + (value >> pageOffsetBits)
                    + " offset=" + (value & ((1L << pageOffsetBits) - 1)) + ")"));
            showResult(va, value, isPageIndex);
            journeyMapManager.updateMapAfterCalculator(player);
            wasSkipped.put(player.getUniqueId(), true);
            if (isPageIndex) {
                long offset = value & ((1L << pageOffsetBits) - 1);
                long pageIndex = offset / (1L << pageOffsetBits);
                String summary = buildPageIndexSummary(pageIndex);
                tracker.setVar(player, "pageIndex", String.valueOf(pageIndex));
                journeyMapManager.updateMap(player);
                player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_skipped", "{summary}", summary));
                askPageIndexQuestion(player, pageIndex);
            } else {
                String summary = buildChatSummary(value);
                player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_skipped", "{summary}", summary));
                askHexQuestion(player, va, value);
            }
        } catch (NumberFormatException e) {
            setCalcError(va);
            player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("errors.calculator.parse_error_va", "{va}", va));
        }
    }

    // ── Calculator verification questions ────────────────────────────────────

    private void askHexQuestion(Player player, String inputHex, long value) {
        int totalBits = pageOffsetBits * 2;
        // Distractor A: nibbles swapped (tests VPN/offset order knowledge)
        // Fall back to top-bit-flipped if both nibbles are identical (e.g. 0xFF, 0x00)
        long swapped = ((value & 0xF) << 4) | ((value >> 4) & 0xF);
        long distractorA = (swapped != value) ? swapped : (value ^ (1L << (totalBits - 1)));

        tracker.setVar(player, "hex", inputHex);
        tracker.setVar(player, "optA", formatNibbles(distractorA, totalBits));
        tracker.setVar(player, "optB", formatNibbles(value, totalBits));       // correct
        tracker.setVar(player, "optC", formatNibbles(value ^ 1L, totalBits)); // last bit flipped

        log.info("[Calc] " + player.getName() + " asking hex_to_binary | hex=" + inputHex
            + " | correct=B | optA=" + formatNibbles(distractorA, totalBits)
            + " | optB=" + formatNibbles(value, totalBits)
            + " | optC=" + formatNibbles(value ^ 1L, totalBits));
        askCalcQuestion(player, "calculator_room.hex_to_binary");
    }

    private void askPageIndexQuestion(Player player, long value) {
        tracker.setVar(player, "optA", String.valueOf(value + 1));
        tracker.setVar(player, "optB", String.valueOf(value + 2));
        tracker.setVar(player, "optC", String.valueOf(value));               // correct

        log.info("[Calc] " + player.getName() + " asking page_index | correct=C"
            + " | optA=" + (value + 1) + " | optB=" + (value + 2) + " | optC=" + value);
        askCalcQuestion(player, "calculator_room.page_index");
    }

    private void askCalcQuestion(Player player, String questionPath) {
        QuestionBank.Question q = questionBank.getQuestion(questionPath);
        if (q == null) {
            log.warning("[Calc] No question found for path: " + questionPath + " — skipping quiz");
            hasCalculated.put(player.getUniqueId(), true);
            return;
        }
        Map<String, String> vars = tracker.getVars(player);
        String questionText = q.text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            questionText = questionText.replace("{" + e.getKey() + "}", e.getValue());
        }
        player.sendMessage("§6[Quiz] §e" + questionText);
        player.sendMessage(q.formatOptions(vars));
        pendingCalcVerify.put(player.getUniqueId(), questionPath);
    }

    @EventHandler
    public void onCalcVerifyChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        String questionPath = pendingCalcVerify.get(player.getUniqueId());
        if (questionPath == null) return;
        event.setCancelled(true);
        String msg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            QuestionBank.Question q = questionBank.getQuestion(questionPath);
            if (q == null) {
                pendingCalcVerify.remove(player.getUniqueId());
                hasCalculated.put(player.getUniqueId(), true);
                return;
            }
            log.info("[Calc] " + player.getName() + " answered '" + msg
                + "' for '" + questionPath + "' | correct=" + q.checkAnswer(msg)
                + " | phase=" + tracker.getPhase(player));
            if (q.checkAnswer(msg)) {
                pendingCalcVerify.remove(player.getUniqueId());
                hasCalculated.put(player.getUniqueId(), true);
                player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_quiz_correct"));
                boolean skipped = wasSkipped.getOrDefault(player.getUniqueId(), false);
                wasSkipped.remove(player.getUniqueId());
                String phase = tracker.getPhase(player);
                if ("calculator_from_tlb".equals(phase)) {
                    String dialoguePath = skipped
                        ? "rooms.calculator_room.from_tlb_skip"
                        : "rooms.calculator_room.from_tlb_after_quiz";
                    log.info("[Calc] " + player.getName() + " post-quiz dialogue: " + dialoguePath);
                    dialogueManager.speakInstant(player, dialoguePath, tracker.getVars(player));
                }
            } else {
                player.sendMessage("§c" + q.wrongFeedback);
                // Re-display the question so they can try again
                Map<String, String> vars = tracker.getVars(player);
                String questionText = q.text;
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    questionText = questionText.replace("{" + e.getKey() + "}", e.getValue());
                }
                player.sendMessage("§6[Quiz] §e" + questionText);
                player.sendMessage(q.formatOptions(vars));
            }
        });
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
            player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("errors.calculator.empty_page"));
            return;
        }
        String pageText = PlainTextComponentSerializer.plainText().serialize(pages.get(0)).trim();
        String input    = pageText.split("\n")[0].trim();
        if (input.isEmpty()) {
            player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("errors.calculator.empty_page"));
            return;
        }

        // Remove book from hopper, return it to the player
        hopper.getInventory().setItem(bookSlot, null);
        player.getInventory().addItem(book);

        String phase = tracker.getPhase(player);
        log.info("[Calc] " + player.getName() + " HOPPER | phase=" + phase + " | input=" + input);
        startCalculation(player, input, phase);
    }

    // ── Calculation sequence ──────────────────────────────────────────────────

    private void startCalculation(Player player, String input, String phase) {
        calculating.put(player.getUniqueId(), true);
        setCalcCalculating();
        player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_processing", "{input}", input));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            calculating.remove(player.getUniqueId());
            try {
                long value   = parseInput(input);
                boolean pageIdx = "calculator_from_lazy_loading".equals(phase);
                log.info("[Calc] " + player.getName() + " result | phase=" + phase
                    + " | input=" + input + " | value=" + value
                    + (pageIdx ? " (pageIndex)" : " (vpn=" + (value >> pageOffsetBits)
                        + " offset=" + (value & ((1L << pageOffsetBits) - 1)) + ")"));
                showResult(input, value, pageIdx);
                // Only update VPN and offset, NOT PFN (PFN comes from TLB or page table)
                journeyMapManager.updateMapAfterCalculator(player);

                if (pageIdx) {
                    // Second visit: page index = offset / page_size
                    long offset = value & ((1L << pageOffsetBits) - 1);
                    long pageIndex = offset / (1L << pageOffsetBits);
                    String summary = buildPageIndexSummary(pageIndex);
                    tracker.setVar(player, "pageIndex", String.valueOf(pageIndex));
                    journeyMapManager.updateMap(player);
                    player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_result", "{summary}", summary));
                    askPageIndexQuestion(player, pageIndex);
                } else {
                    // First visit (calculator_from_tlb): ask hex→binary verification question
                    String summary = buildChatSummary(value);
                    player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("feedback.calculator_result", "{summary}", summary));
                    askHexQuestion(player, input, value);
                }
            } catch (NumberFormatException e) {
                log.warning("[Calc] " + player.getName() + " parse error | phase=" + phase + " | input=" + input);
                setCalcError(input);
                player.sendMessage(((com.oscity.OSCity) plugin).getConfigManager().getMessage("errors.calculator.parse_error", "{input}", input));
            }
        }, 100L); // 5 seconds = 100 ticks
    }

    // ── Computation ───────────────────────────────────────────────────────────

    private long parseInput(String raw) throws NumberFormatException {
        String s = raw.trim();
        
        // Handle division expressions (e.g., "0xE/0x10" for page index)
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length == 2) {
                long numerator = parseSingleValue(parts[0].trim());
                long denominator = parseSingleValue(parts[1].trim());
                if (denominator == 0) {
                    throw new NumberFormatException("Division by zero");
                }
                return numerator / denominator;  // Integer division
            }
        }
        
        return parseSingleValue(s);
    }

    private long parseSingleValue(String s) throws NumberFormatException {
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
        String vpnHex = "0x" + Long.toHexString(vpn).toUpperCase();
        String offHex = "0x" + Long.toHexString(off).toUpperCase();
        String vpnBin = formatNibbles(vpn, pageOffsetBits);
        String offBin = formatNibbles(off, pageOffsetBits);
        return "Binary=" + binary
            + ", VPN=" + vpnHex + " (" + vpnBin + ")"
            + ", Offset=" + offHex + " (" + offBin + ")";
    }

    /** Build summary for page index calculation (visit 2). */
    private String buildPageIndexSummary(long value) {
        return "Page Index=" + value + " (0x" + Long.toHexString(value).toUpperCase() + ")";
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

    private void showResult(String input, long value, boolean isPageIndex) {
        if (calcFrames.size() < 6) return;

        if (isPageIndex) {
            String hexVal = "0x" + Long.toHexString(value).toUpperCase();
            setFrame(calcFrames.get(0), "== RESULT ==", "Input: " + shorten(input, 11), "", "");
            setFrame(calcFrames.get(1), "PAGE INDEX:", String.valueOf(value), hexVal, "");
            setFrame(calcFrames.get(2), "Added to log!", "", "", "");
            clearFrame(calcFrames.get(3));
            clearFrame(calcFrames.get(4));
            clearFrame(calcFrames.get(5));
        } else {
            int totalBits = pageOffsetBits * 2;
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
        java.util.Collection<Entity> nearby = loc.getWorld().getNearbyEntities(center, 2.0, 2.0, 2.0);

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
        if (closest == null) {
            plugin.getLogger().warning("CalculatorListener: no item frame found near ("
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
