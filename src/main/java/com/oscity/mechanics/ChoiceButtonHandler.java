package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.content.QuestionBank;
import com.oscity.gamification.ProgressTracker;
import com.oscity.mode.PlayerMode;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.journey.Journey;
import com.oscity.journey.JourneyManager;
import com.oscity.session.JourneyTracker;
import com.oscity.world.LocationRegistry;
import com.oscity.mechanics.JourneyMapManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all choice button presses and subsequent chat-based quiz answers.
 *
 * Smart multi-state buttons (cancel event to prevent TeleportManager double-fire):
 *   ramMix      – CONFIRM/RETRY/CONTINUE/FINISH in RAM Room
 *   perTerminate – exit button in Permission Chamber (TP or sign update)
 *   cowToRam    – Go to RAM from COW Room (phase-conditional)
 *   loadingTp   – Go to Calculator or Disk from Lazy Loading Room
 *   btnLazyAlloc – Deny write / Go to COW room in Lazy Allocation Room
 *
 * Regular phase-dispatched buttons:
 *   "permission_decision" → btn1-4
 *   "page_fault_type"     → btn1-3
 *   "lazy_alloc_decision" → allocateLazy / swapLazy
 *   "lazy_alloc_cow"      → cowLazyAlloc / btnLazyAlloc / nothing
 *   "cow_decision"        → allocateCow / terminate
 *   "going_to_cow"        → btnLazyAlloc (TP to COW)
 */
public class ChoiceButtonHandler implements Listener {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;
    private final DialogueManager dialogue;
    private final QuestionBank questionBank;
    private final ProgressTracker progress;
    private final LocationRegistry locationRegistry;
    private final CalculatorListener calculatorListener;
    private final SwapClockManager swapClockManager;
    private final JourneyMapManager journeyMapManager;
    private final PageTableManager pageTableManager;

    // Map: button location → config key name
    private final Map<Location, String> buttons = new HashMap<>();

    // Adventurer mode chest location for access control
    private Location adventurerChestLoc = null;

    // Per-player pending state: waiting for YES/NO confirmation
    private final Map<UUID, PendingChoice> pendingConfirm = new HashMap<>();
    // Per-player pending state: waiting for quiz answer
    private final Map<UUID, PendingQuiz> pendingQuiz = new HashMap<>();

    // ── Inner state classes ───────────────────────────────────────────────────

    private static class PendingChoice {
        final String buttonKey;
        PendingChoice(String buttonKey) { this.buttonKey = buttonKey; }
    }

    private static class PendingQuiz {
        final String questionPath;
        final String onCorrectPhase;
        final String onCorrectDialoguePath;
        PendingQuiz(String questionPath, String onCorrectPhase, String onCorrectDialoguePath) {
            this.questionPath = questionPath;
            this.onCorrectPhase = onCorrectPhase;
            this.onCorrectDialoguePath = onCorrectDialoguePath;
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public ChoiceButtonHandler(JavaPlugin plugin, JourneyTracker tracker,
                               DialogueManager dialogue, QuestionBank questionBank,
                               ProgressTracker progress, LocationRegistry locationRegistry,
                               CalculatorListener calculatorListener,
                               SwapClockManager swapClockManager,
                               JourneyMapManager journeyMapManager,
                               PageTableManager pageTableManager) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.dialogue = dialogue;
        this.questionBank = questionBank;
        this.progress = progress;
        this.locationRegistry = locationRegistry;
        this.calculatorListener = calculatorListener;
        this.swapClockManager = swapClockManager;
        this.journeyMapManager = journeyMapManager;
        this.pageTableManager = pageTableManager;
        loadButtons();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("ChoiceButtonHandler registered.");
    }

    // ── Button loading ────────────────────────────────────────────────────────

    private void loadButtons() {
        buttons.clear();

        // Load choiceButtons
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("choiceButtons");
        if (sec == null) {
            plugin.getLogger().warning("ChoiceButtonHandler: no 'choiceButtons' in config.yml");
        } else {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection btn = sec.getConfigurationSection(key);
                if (btn == null) continue;
                String worldName = btn.getString("world");
                if (worldName == null) continue;
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;
                Location loc = new Location(world, btn.getInt("x"), btn.getInt("y"), btn.getInt("z"));
                buttons.put(loc, key);
            }
        }

        // Also register phase-dependent TP buttons that need smart (phase-based) handling.
        // cowToRam: COW room → RAM, only active after correct cow decision.
        // loadingTp: Lazy Loading → Calculator or Disk, based on phase.
        // diskToRam: Disk room → RAM, only active after taking swap slot book.
        registerFromTpButtons("cowToRam", "cowToRam");
        registerFromTpButtons("loadingToCalc", "loadingTp");  // loadingToDisk shares same location
        registerFromTpButtons("diskToRam", "diskToRam");

        // Register mode-start buttons that require journey selection first
        registerFromTpButtons("adventurerStart", "adventurerStart");
        registerFromTpButtons("learnerStart", "learnerStart");

        // Register calculator room continue buttons
        registerFromTpButtons("calcToTlb", "calcToTlb");
        registerFromTpButtons("calcToLazyLoading", "calcToLazyLoading");

        // Register swap district teleport button (phase-gated)
        registerFromTpButtons("swapToRam", "swapToRam");

        // Register page table to permission chamber buttons (require correct PTE)
        registerFromTpButtons("pt1ToChamber", "pt1ToChamber");
        registerFromTpButtons("pt2ToChamber", "pt2ToChamber");
        registerFromTpButtons("pt3ToChamber", "pt3ToChamber");

        // Register end terminal restart button
        registerFromTpButtons("endToStart", "endToStart");

        // Register doorOpen buttons (buttons and pressure plates that open physical doors)
        registerFromDoorOpen("toPageTable");
        registerFromDoorOpen("toPageFaultCorridor");
        registerFromDoorOpen("toLazyLoading");
        registerFromDoorOpen("toLazyAllocation");

        // Load adventurer chest location for access control
        loadAdventurerChestLocation();

        plugin.getLogger().info("ChoiceButtonHandler: loaded " + buttons.size() + " choice/smart buttons.");
    }

    private void loadAdventurerChestLocation() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("chests.adventurerChest");
        if (sec == null) return;
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        adventurerChestLoc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
    }

    private void registerFromTpButtons(String tpKey, String smartKey) {
        ConfigurationSection btn = plugin.getConfig().getConfigurationSection("tpButtons." + tpKey);
        if (btn == null) {
            plugin.getLogger().warning("[CalcContinue] No config for tpButtons." + tpKey);
            return;
        }
        String worldName = btn.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        Location loc = new Location(world, btn.getInt("x"), btn.getInt("y"), btn.getInt("z"));
        buttons.put(loc, smartKey);
        plugin.getLogger().info("[CalcContinue] Registered button " + smartKey + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    private void registerFromDoorOpen(String key) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("doorOpen." + key);
        if (sec == null) {
            plugin.getLogger().warning("[DoorOpen] No config for doorOpen." + key);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        Location loc = new Location(world, sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        buttons.put(loc, key);
        plugin.getLogger().info("[DoorOpen] Registered " + key + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    // ── Button press ─────────────────────────────────────────────────────────

    /**
     * LOW priority so this fires before TeleportManager (NORMAL).
     * Cancels the event for any button in our map, preventing double-handling.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onButtonPress(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        String blockTypeName = event.getClickedBlock().getType().name();
        if (!blockTypeName.endsWith("_BUTTON") && !blockTypeName.equals("LEVER")) return;

        Location clicked = event.getClickedBlock().getLocation();
        String buttonKey = findButton(clicked);
        if (buttonKey == null) return;

        // Prevent TeleportManager from also handling this button
        event.setCancelled(true);

        Player player = event.getPlayer();
        String phase = tracker.getPhase(player);
        Journey journey = tracker.getJourney(player);
        Map<String, String> vars = tracker.getVars(player);

        // ── Smart multi-state buttons ─────────────────────────────────────
        if ("ramMix".equals(buttonKey)) {
            handleRamMixButton(player, phase, journey);
            return;
        }

        if ("perTerminate".equals(buttonKey)) {
            handlePermExitButton(player, phase, journey);
            return;
        }

        if ("cowToRam".equals(buttonKey)) {
            handleCowToRamButton(player, phase);
            return;
        }

        if ("loadingTp".equals(buttonKey)) {
            handleLoadingTpButton(player, phase);
            return;
        }

        if ("diskToRam".equals(buttonKey)) {
            handleDiskToRamButton(player, phase, journey);
            return;
        }

        if ("skipCalc".equals(buttonKey)) {
            // Skip is only for learner mode
            if (tracker.getMode(player) == PlayerMode.ADVENTURER) {
                player.sendMessage("§cAdventurer mode: you must calculate the answer yourself!");
                return;
            }
            calculatorListener.skipCalculation(player);
            return;
        }

        if ("adventurerStart".equals(buttonKey)) {
            handleModeStartButton(player, "adventurerStart", "tlbSpawn");
            return;
        }

        if ("learnerStart".equals(buttonKey)) {
            handleModeStartButton(player, "learnerStart", "tlbSpawn");
            return;
        }

        if ("endToStart".equals(buttonKey)) {
            handleEndToStart(player);
            return;
        }

        // ── Swap District: clock algorithm frame buttons (frame1btn–frame6btn) ──
        if (buttonKey.startsWith("frame") && buttonKey.endsWith("btn")) {
            try {
                int frameNum = Integer.parseInt(buttonKey.substring(5, buttonKey.length() - 3));
                if (frameNum >= 1 && frameNum <= 6) {
                    swapClockManager.handleFrameButton(player, frameNum);
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        // ── Swap District: eviction lever ─────────────────────────────────────
        if ("swapLever".equals(buttonKey)) {
            swapClockManager.handleLeverPull(player);
            return;
        }

        // ── TLB Room: hit/miss decision buttons ───────────────────────────────
        if ("hit".equals(buttonKey)) {
            handleTLBDecision(player, true);
            return;
        }
        if ("miss".equals(buttonKey)) {
            handleTLBDecision(player, false);
            return;
        }

        // ── Calculator Room: continue buttons ─────────────────────────────────
        if ("calcToTlb".equals(buttonKey) || "calcToLazyLoading".equals(buttonKey)) {
            plugin.getLogger().info("[CalcContinue] Button press detected: " + buttonKey);
            handleCalculatorContinue(player, buttonKey);
            return;
        }

        // ── Swap District: teleport to RAM button ─────────────────────────────
        if ("swapToRam".equals(buttonKey)) {
            handleSwapToRam(player);
            return;
        }

        // ── Page Table: teleport to Permission Chamber (requires correct PTE) ─
        if ("pt1ToChamber".equals(buttonKey) || "pt2ToChamber".equals(buttonKey)
                || "pt3ToChamber".equals(buttonKey)) {
            handlePageTableToChamber(player, buttonKey);
            return;
        }

        // ── Phase-dispatched buttons ──────────────────────────────────────
        switch (phase) {
            case "permission_decision":
                handlePermissionDecision(player, buttonKey, journey, vars);
                break;
            case "page_fault_type":
                handlePageFaultType(player, buttonKey, journey, vars);
                break;
            case "lazy_alloc_decision":
                handleLazyAllocDecision(player, buttonKey, journey, vars);
                break;
            case "lazy_alloc_cow":
                handleLazyAllocCow(player, buttonKey, journey, vars);
                break;
            case "cow_decision":
                handleCowDecision(player, buttonKey, journey, vars);
                break;
            case "going_to_cow":
                // After confirming cowLazyAlloc in Lazy Alloc Room, btnLazyAlloc → TP to COW
                if ("btnLazyAlloc".equals(buttonKey)) {
                    teleportPlayer(player, "cowRoom");
                }
                break;
            case "page_directory":
                // Page table door button - only works after answering TLB miss quiz
                if ("toPageTable".equals(buttonKey)) {
                    // Check journey-specific prerequisites
                    if (journey == Journey.LUCKY) {
                        // Lucky journey: check if TLB hit/miss quiz was answered
                        // After correct answer, phase changes to ram_allow_access
                        if (!"ram_allow_access".equals(phase) && !"tlb_miss_quiz".equals(phase)) {
                            player.sendMessage("§c[TLB Room] Answer the TLB hit/miss quiz first!");
                            return;
                        }
                    } else {
                        // Other journeys: check prerequisites in order
                        
                        // First check: did player visit calculator room?
                        if (!calculatorListener.hasPlayerCalculated(player)) {
                            player.sendMessage("§c[TLB Room] You need to visit the Calculator Room first to get your VPN!");
                            return;
                        }
                        
                        // Second check: did player make TLB hit/miss decision?
                        // After decision, phase changes to tlb_miss_quiz (for miss) or ram_allow_access (for hit)
                        if ("tlb_spawn".equals(phase) || "tlb_after_calculator".equals(phase)) {
                            player.sendMessage("§c[TLB Room] Decide if this is a TLB hit or miss first!");
                            return;
                        }
                    }
                    openDoor("tlbToPt");
                }
                break;
            default:
                break;
        }
    }

    // ── Chat listener (YES/NO confirms and quiz answers) ─────────────────────

    /**
     * Blocks opening the adventurer mode chest until the player has chosen a journey.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (adventurerChestLoc == null) return;

        // Check if opening the adventurer chest
        if (event.getInventory().getHolder() instanceof org.bukkit.block.Chest chest) {
            Location chestLoc = chest.getLocation();
            if (chestLoc.getWorld() != null && chestLoc.getWorld().equals(adventurerChestLoc.getWorld())
                    && chestLoc.getBlockX() == adventurerChestLoc.getBlockX()
                    && chestLoc.getBlockY() == adventurerChestLoc.getBlockY()
                    && chestLoc.getBlockZ() == adventurerChestLoc.getBlockZ()) {
                String phase = tracker.getPhase(player);
                if (!"terminal_journey_chosen".equals(phase)) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou must select a journey first! Use the terminal to choose your journey.");
                }
            }
        }
    }

    /**
     * Blocks access to the adventurer mode chest until the player has chosen a journey.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (adventurerChestLoc == null) return;

        // Check if clicking in the adventurer chest by comparing block location
        if (event.getInventory().getHolder() instanceof org.bukkit.block.Chest chest) {
            Location chestLoc = chest.getLocation();
            if (chestLoc.getWorld() != null && chestLoc.getWorld().equals(adventurerChestLoc.getWorld())
                    && chestLoc.getBlockX() == adventurerChestLoc.getBlockX()
                    && chestLoc.getBlockY() == adventurerChestLoc.getBlockY()
                    && chestLoc.getBlockZ() == adventurerChestLoc.getBlockZ()) {
                String phase = tracker.getPhase(player);
                if (!"terminal_journey_chosen".equals(phase)) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou must select a journey first! Use the terminal to choose your journey.");
                }
            }
        }

        // Check if placing swap PFN book in RAM room chest (for SWAPPED_OUT journey)
        if (event.getInventory().getHolder() instanceof org.bukkit.block.Chest ramChest) {
            Location chestLoc = ramChest.getLocation();
            plugin.getLogger().info("[RAMChest] Chest click at: " + chestLoc.getBlockX() + "," + chestLoc.getBlockY() + "," + chestLoc.getBlockZ());
            plugin.getLogger().info("[RAMChest] Action: " + event.getAction());
            plugin.getLogger().info("[RAMChest] isRAMRoomChest: " + isRAMRoomChest(chestLoc));
            
            // Check if this is any of the RAM room chests
            if (isRAMRoomChest(chestLoc)) {
                // This is the RAM room chest
                if (event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                        || event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ONE) {
                    // Use getCursor() - this is the item being placed (getCurrentItem() is the slot content, which is null)
                    ItemStack item = event.getCursor();
                    plugin.getLogger().info("[RAMChest] Cursor item: " + (item != null ? item.getType() : "null"));
                    if (item != null) {
                        String phase = tracker.getPhase(player);
                        plugin.getLogger().info("[RAMChest] Phase: " + phase);
                        boolean validBook = false;

                        // Check for FILLED_MAP (from swap district eviction)
                        if (item.getType() == org.bukkit.Material.FILLED_MAP) {
                            validBook = "swap_after_eviction".equals(phase);
                            plugin.getLogger().info("[RAMChest] FILLED_MAP check: " + validBook);
                        }
                        // Check for WRITTEN_BOOK (from disk room swap slot 0)
                        else if (item.getType() == org.bukkit.Material.WRITTEN_BOOK
                                && item.hasItemMeta()
                                && "disk_swap_retrieval".equals(phase)) {
                            org.bukkit.inventory.meta.BookMeta bookMeta = (org.bukkit.inventory.meta.BookMeta) item.getItemMeta();
                            String displayName = bookMeta.getDisplayName();
                            String title = bookMeta.getTitle();
                            plugin.getLogger().info("[RAMChest] Found book - displayName: " + displayName + ", title: " + title);

                            // Check both display name and title
                            boolean isSwapSlot0 = false;
                            if (displayName != null && (displayName.contains("Swap Slot 0") || displayName.contains("SwapSlot0"))) {
                                isSwapSlot0 = true;
                            } else if (title != null && (title.contains("Swap Slot 0") || title.contains("SwapSlot0"))) {
                                isSwapSlot0 = true;
                            }
                            validBook = isSwapSlot0;
                            plugin.getLogger().info("[RAMChest] validBook=" + validBook);
                        }
                        // Check for WRITTEN_BOOK or WRITABLE_BOOK (Process 5's private copy for PURE_COW journey)
                        else if ((item.getType() == org.bukkit.Material.WRITTEN_BOOK || item.getType() == org.bukkit.Material.WRITABLE_BOOK)
                                && item.hasItemMeta()
                                && "ram_after_cow".equals(phase)) {
                            org.bukkit.inventory.meta.BookMeta bookMeta = (org.bukkit.inventory.meta.BookMeta) item.getItemMeta();
                            String displayName = bookMeta.getDisplayName();
                            String title = bookMeta.getTitle();
                            plugin.getLogger().info("[RAMChest] Found book - displayName: " + displayName + ", title: " + title);

                            // For Pure COW: validate the book content is exactly "hello"
                            // (The book from frame 0x2 chest is the only writable book for this phase)
                            List<Component> pages = bookMeta.pages();
                            if (pages != null && !pages.isEmpty()) {
                                String content = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                    .plainText().serialize(pages.get(0)).trim();
                                plugin.getLogger().info("[RAMChest] Book content: '" + content + "'");
                                if (!"hello".equalsIgnoreCase(content)) {
                                    player.sendMessage("§c[RAM] Make sure you wrote exactly what the process asked you to write!");
                                    validBook = false;
                                } else {
                                    validBook = true;
                                    plugin.getLogger().info("[RAMChest] Book content is correct!");
                                }
                            } else {
                                player.sendMessage("§c[RAM] The book is empty! Write 'hello' in it.");
                                validBook = false;
                            }
                            plugin.getLogger().info("[RAMChest] Process 5 book valid=" + validBook);
                        }

                        if (validBook) {
                            // Track which book was placed
                            if ("disk_swap_retrieval".equals(phase)) {
                                tracker.setVar(player, "swapBookPlaced", "true");
                                player.sendMessage("§a[RAM] Page placed! Press the button to finish.");
                            } else if ("ram_after_cow".equals(phase)) {
                                tracker.setVar(player, "cowBookPlaced", "true");
                                player.sendMessage("§a[RAM] Book placed! Press the button to finish.");
                                // Show success dialogue for Pure COW
                                Journey playerJourney = tracker.getJourney(player);
                                if (playerJourney == Journey.PURE_COW) {
                                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        dialogue.speak(player, "rooms.ram_room.after_cow_pure_success", tracker.getVars(player));
                                    }, 40L);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if a chest location is one of the RAM room chests.
     */
    private boolean isRAMRoomChest(Location chestLoc) {
        ConfigurationSection ramSec = plugin.getConfig().getConfigurationSection("chests.ramRoom");
        if (ramSec == null) return false;

        for (String key : ramSec.getKeys(false)) {
            ConfigurationSection chestSec = ramSec.getConfigurationSection(key);
            if (chestSec != null) {
                String worldName = chestSec.getString("world");
                if (worldName == null) continue;
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                int chestX = chestSec.getInt("x");
                int chestY = chestSec.getInt("y");
                int chestZ = chestSec.getInt("z");

                if (chestLoc.getWorld() != null && chestLoc.getWorld().equals(world)
                        && chestLoc.getBlockX() == chestX
                        && chestLoc.getBlockY() == chestY
                        && chestLoc.getBlockZ() == chestZ) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // ── Pending confirmation (1 = YES / 2 = NO) ──────────────────────────
        if (pendingConfirm.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            PendingChoice pending = pendingConfirm.get(player.getUniqueId());
            if (msg.equals("1")) {
                pendingConfirm.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () ->
                    resolveConfirmedChoice(player, pending.buttonKey));
            } else if (msg.equals("2")) {
                pendingConfirm.remove(player.getUniqueId());
                player.sendMessage(Component.text("§7Choice cancelled. Press the button again when ready.", NamedTextColor.GRAY));
            } else {
                QuestionBank.Question q = questionBank.getQuestion("general.confirm_choice");
                if (q != null) {
                    player.sendMessage("§c" + q.wrongFeedback);
                }
            }
            return;
        }

        // ── Pending quiz answer ───────────────────────────────────────────────
        if (pendingQuiz.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            PendingQuiz quiz = pendingQuiz.get(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                QuestionBank.Question q = questionBank.getQuestion(quiz.questionPath);
                if (q == null) {
                    pendingQuiz.remove(player.getUniqueId());
                    return;
                }
                if (q.checkAnswer(msg)) {
                    pendingQuiz.remove(player.getUniqueId());
                    tracker.setPhase(player, quiz.onCorrectPhase);
                    dialogue.speak(player, quiz.onCorrectDialoguePath, tracker.getVars(player));
                } else {
                    SQLiteStudyDatabase.logWrongAnswer(
                        tracker.getVar(player, "sessionId"),
                        tracker.getPhase(player)
                    );
                    player.sendMessage("§c" + q.wrongFeedback);
                    sendQuestion(player, q);
                }
            });
            return;
        }

        // ── Adventure mode journey selection (chat "1"–"8") ──────────────────
        if ("adventurer_select".equals(tracker.getPhase(player))) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> handleJourneySelection(player, msg));
        }
    }

    // ── RAM Room multi-state button ───────────────────────────────────────────

    /**
     * The ramMix button cycles through: CONFIRM → (RETRY →) CONTINUE/FINISH
     * depending on the current journey and phase.
     */
    private void handleRamMixButton(Player player, String phase, Journey journey) {
        switch (phase) {
            case "ram_allow_access":
                String nextPhase = JourneyManager.nextPhaseAfterRamConfirm(journey);
                String[] sign = JourneyManager.ramSignAfterConfirm(journey);
                tracker.setPhase(player, nextPhase);
                updateSign("ramRoom.mixSign", sign[0], sign[1], sign[2], sign[3]);
                break;

            case "ram_retry_tlb_miss":
                // RETRY INSTRUCTION → FINISH (TLB Miss - No Fault)
                tracker.setPhase(player, "ram_finish");
                updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                break;

            case "ram_after_lazy_alloc":
                // RETRY INSTRUCTION → CONTINUE (go to Lazy Alloc Room second visit)
                tracker.setPhase(player, "ram_continue_to_lazy_alloc");
                updateSign("ramRoom.mixSign", "CONTINUE", "", "", "");
                break;

            case "ram_continue_to_lazy_alloc":
                // CONTINUE → TP to Lazy Allocation Room (second visit for COW decision)
                teleportPlayer(player, "lazyAllocationRoom");
                break;

            case "ram_after_cow":
                if (JourneyManager.needsSwapAfterCow(journey)) {
                    // CONTINUE → TP to Swap District (Lazy Allocation path)
                    teleportPlayer(player, "swapDistrict");
                } else {
                    // Pure COW: Check if player has placed the book back
                    String cowBookPlaced = tracker.getVar(player, "cowBookPlaced");
                    if ("true".equals(cowBookPlaced)) {
                        // Book placed, now can finish
                        tracker.setPhase(player, "ram_finish");
                        updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                    } else {
                        // Check if player has the Process 5 book (private copy) in inventory
                        boolean hasProcess5Book = false;
                        for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                            if (item != null && (item.getType() == org.bukkit.Material.WRITTEN_BOOK || item.getType() == org.bukkit.Material.WRITABLE_BOOK)
                                    && item.hasItemMeta()) {
                                // For Pure COW, any writable/written book in inventory is the Process 5 book
                                hasProcess5Book = true;
                                break;
                            }
                        }
                        if (hasProcess5Book) {
                            player.sendMessage("§c[RAM] Write 'hello' in the book and place it back in the frame 0x2 chest!");
                        } else {
                            player.sendMessage("§c[RAM] Take the book from frame 0x2 chest (your private copy), write 'hello' in it, and put it back!");
                        }
                    }
                }
                break;

            case "disk_swap_retrieval":
                // SWAPPED_OUT: Player just arrived from disk with swap slot 0 book
                // Check if player has placed the book in the RAM chest
                String bookPlacedDisk = tracker.getVar(player, "swapBookPlaced");
                if ("true".equals(bookPlacedDisk)) {
                    // Book placed, now can finish
                    tracker.setPhase(player, "ram_finish");
                    updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                } else {
                    // Check if player has the swap book in inventory and needs to place it
                    boolean hasSwapBook = false;
                    for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                        if (item != null && item.getType() == org.bukkit.Material.WRITTEN_BOOK
                                && item.hasItemMeta()) {
                            String displayName = item.getItemMeta().getDisplayName();
                            if (displayName != null && displayName.contains("Swap Slot 0")) {
                                hasSwapBook = true;
                                break;
                            }
                        }
                    }
                    if (hasSwapBook) {
                        player.sendMessage("§c[RAM] Place the swap slot 0 book in the chest first!");
                    } else {
                        player.sendMessage("§c[RAM] You need the page from swap slot 0! Go to the disk room and retrieve it.");
                    }
                }
                break;

            case "disk_lazy_loading":
                // LAZY_LOADING from disk: CONTINUE → TP to Swap District
                teleportPlayer(player, "swapDistrict");
                break;

            case "swap_after_eviction":
                // SWAPPED_OUT: Player has taken swap chest book, pressed button → FINISH
                String bookPlaced = tracker.getVar(player, "swapBookPlaced");
                if ("true".equals(bookPlaced)) {
                    tracker.setPhase(player, "ram_finish");
                    updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                } else {
                    player.sendMessage("§cYou must place the PFN book in the chest first!");
                }
                break;

            case "swap_lazy_loading":
                // LAZY_LOADING back from Swap: RETRY INSTRUCTION → FINISH
                tracker.setPhase(player, "ram_finish");
                updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                break;

            case "swap_lazy_alloc":
                // LAZY_ALLOCATION back from Swap: RETRY INSTRUCTION → FINISH
                tracker.setPhase(player, "ram_finish");
                updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                break;

            case "ram_finish":
                // FINISH → mark journey complete and TP to End Terminal
                plugin.getLogger().info("[RamMix] ram_finish: journey=" + journey + " progress=" + (progress != null));
                if (journey != null && !progress.isComplete(player, journey)) {
                    progress.markComplete(player, journey);
                    plugin.getLogger().info("[RamMix] Marked journey complete: " + journey);
                }
                plugin.getLogger().info("[RamMix] Teleporting to endTerminal");
                teleportPlayer(player, "endTerminal");
                break;

            default:
                break;
        }
    }

    // ── Permission Chamber exit button ────────────────────────────────────────

    /**
     * The perTerminate button at the exit wall acts as a TP button after a
     * correct permission decision, or as the "Terminate Process" action for segfault.
     */
    private void handlePermExitButton(Player player, String phase, Journey journey) {
        switch (phase) {
            case "ram_allow_access":
                // TLB Miss - No Fault correctly chose Allow Access → TP to RAM
                teleportPlayer(player, "ramRoom");
                break;

            case "disk_swap_retrieval":
                // Swapped-Out Page correctly chose Page Fault → TP to Disk
                teleportPlayer(player, "diskRoom");
                break;

            case "cow_decision":
                // Pure COW correctly chose Protection Fault → TP to COW Room
                if (journey == Journey.PURE_COW) {
                    teleportPlayer(player, "cowRoom");
                }
                break;

            case "segfault_end":
                // Permission Violation: "Terminate process and finish" → show Finish sign
                tracker.setPhase(player, "segfault_finish");
                updateSign("perChamber.sign6", "Finish", "", "", "");
                break;

            case "segfault_finish":
                // Finish pressed → TP to End Terminal
                teleportPlayer(player, "endTerminal");
                break;

            case "lazy_alloc_decision":
                // Lazy Allocation: go to Page Fault Corridor (door button handles this)
                player.sendMessage("§c[Permission Chamber] Use the door button to proceed to the Lazy Allocation Room!");
                break;

            case "lazy_loading_entered":
                // Lazy Loading: go to Page Fault Corridor (door button handles this)
                player.sendMessage("§c[Permission Chamber] Use the door button to proceed to the Lazy Loading Room!");
                break;

            default:
                // Wrong phase/journey for this button
                player.sendMessage("§c[Permission Chamber] Make your choice using the buttons first!");
                break;
        }
    }

    // ── COW Room → RAM TP button ──────────────────────────────────────────────

    private void handleCowToRamButton(Player player, String phase) {
        if ("ram_after_cow".equals(phase)) {
            teleportPlayer(player, "ramRoom");
        }
    }

    // ── Lazy Loading Room TP button ───────────────────────────────────────────

    private void handleLoadingTpButton(Player player, String phase) {
        if ("lazy_loading_entered".equals(phase)) {
            teleportPlayer(player, "calculatorRoom");
        } else if ("lazy_loading_returned".equals(phase)) {
            teleportPlayer(player, "diskRoom");
        }
    }

    // ── Disk Room: teleport to RAM ────────────────────────────────────────────

    /**
     * Handles the diskToRam button for SWAPPED_OUT journey.
     * Only allows teleport if the player has taken the swap slot 0 book.
     */
    private void handleDiskToRamButton(Player player, String phase, Journey journey) {
        if ("disk_swap_retrieval".equals(phase)) {
            // Check if player has the swap slot 0 book in inventory
            boolean hasSwapBook = false;
            for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == org.bukkit.Material.WRITTEN_BOOK
                        && item.hasItemMeta()) {
                    org.bukkit.inventory.meta.BookMeta bookMeta = (org.bukkit.inventory.meta.BookMeta) item.getItemMeta();
                    String displayName = bookMeta.getDisplayName();
                    String title = bookMeta.getTitle();
                    plugin.getLogger().info("[DiskToRam] Found book - displayName: " + displayName + ", title: " + title);
                    
                    // Check both display name and title
                    boolean isSwapSlot0 = false;
                    if (displayName != null && (displayName.contains("Swap Slot 0") || displayName.contains("SwapSlot0"))) {
                        isSwapSlot0 = true;
                    } else if (title != null && (title.contains("Swap Slot 0") || title.contains("SwapSlot0"))) {
                        isSwapSlot0 = true;
                    }
                    
                    if (isSwapSlot0) {
                        hasSwapBook = true;
                        plugin.getLogger().info("[DiskToRam] Valid swap book found!");
                        break;
                    }
                }
            }
            plugin.getLogger().info("[DiskToRam] hasSwapBook=" + hasSwapBook + " phase=" + phase);
            if (hasSwapBook) {
                teleportPlayer(player, "ramRoom");
            } else {
                player.sendMessage("§c[Disk Room] You must retrieve the page from swap slot 0 first!");
            }
        } else {
            // For other journeys, just teleport
            teleportPlayer(player, "ramRoom");
        }
    }

    // ── Swap District: teleport to RAM ────────────────────────────────────────

    /**
     * Handles the swapToRam button - only works after completing swap algorithm.
     */
    private void handleSwapToRam(Player player) {
        String phase = tracker.getPhase(player);
        if ("swap_after_eviction".equals(phase)) {
            teleportPlayer(player, "ramRoom");
        } else {
            player.sendMessage("§cComplete the swap algorithm first! Find the victim frame and pull the lever.");
        }
    }

    // ── Page Table: teleport to Permission Chamber ────────────────────────────

    /**
     * Handles the pt1ToChamber, pt2ToChamber, pt3ToChamber buttons.
     * Only allows teleport if the player has the correct PTE map in their inventory.
     */
    private void handlePageTableToChamber(Player player, String buttonKey) {
        String vpnHex = tracker.getVar(player, "vpnHex");
        if ("?".equals(vpnHex) || vpnHex.isEmpty()) {
            player.sendMessage("§c[Page Table] No VPN found! Complete the calculator first.");
            return;
        }

        // Calculate correct chest index from TABLE_BITS (lower 2 bits of VPN)
        int vpnValue = parseVpnHex(vpnHex);
        int correctChestIndex = vpnValue & 0x3; // TABLE_BITS

        // Check if player has the correct PTE map
        boolean hasCorrectPTE = false;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == org.bukkit.Material.FILLED_MAP
                    && item.hasItemMeta()) {
                String displayName = item.getItemMeta().getDisplayName();
                if (displayName != null && displayName.contains("PTE Map")) {
                    // Extract chest index from display name "§bPTE Map §7(PTX ChestY)"
                    if (displayName.contains("Chest" + correctChestIndex)) {
                        hasCorrectPTE = true;
                        break;
                    }
                }
            }
        }

        if (hasCorrectPTE) {
            teleportPlayer(player, "permissionChamber");
        } else {
            player.sendMessage("§c[Page Table] You need the correct PTE map from Chest " 
                + correctChestIndex + " to proceed!");
        }
    }

    // ── Mode start buttons (adventurerStart / learnerStart) ───────────────────

    /**
     * Handles the adventurerStart and learnerStart buttons.
     * Only allows teleport if the player has chosen a journey AND taken the map from the chest.
     */
    private void handleModeStartButton(Player player, String buttonKey, String destination) {
        String phase = tracker.getPhase(player);
        if (!"terminal_journey_chosen".equals(phase)) {
            player.sendMessage("§cYou must select a journey first! Use the terminal to choose your journey.");
            return;
        }
        
        // Check if player has taken the journey map from the chest
        boolean hasMap = false;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == org.bukkit.Material.FILLED_MAP
                    && item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.MapMeta) {
                hasMap = true;
                break;
            }
        }
        if (!hasMap) {
            player.sendMessage("§cYou must take the journey map from the chest first!");
            return;
        }
        
        teleportPlayer(player, destination);
    }

    // ── End Terminal: Restart Journey ─────────────────────────────────────────

    /**
     * Handles the endToStart button - teleports player to initial terminal
     * and resets their journey state for a new journey.
     * Database (journey completions) is preserved.
     */
    private void handleEndToStart(Player player) {
        plugin.getLogger().info("[EndToStart] Resetting journey state for " + player.getName());

        // Reset phase to terminal spawn
        tracker.setPhase(player, "terminal_spawn");

        // Clear journey vars (but keep session ID and learner journey counter)
        String sessionId = tracker.getVar(player, "sessionId");
        String learnerJourneyNum = tracker.getVar(player, "learnerJourneyNum");
        tracker.clearVars(player);
        if (sessionId != null && !sessionId.isEmpty()) {
            tracker.setVar(player, "sessionId", sessionId);
        }
        if (learnerJourneyNum != null && !"?".equals(learnerJourneyNum)) {
            tracker.setVar(player, "learnerJourneyNum", learnerJourneyNum);
        }

        // Clear journey (player will choose again)
        tracker.setJourney(player, null);

        // Teleport to initial terminal
        teleportPlayer(player, "initialSpawn");

        // Speak initial dialogue
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dialogue.speak(player, "rooms.terminal.initial_spawn", tracker.getVars(player));
        }, 20L);

        player.sendMessage("§a[Journey Reset] Starting fresh! Choose your journey in the terminal.");
    }

    // ── TLB Room: hit/miss decision ───────────────────────────────────────────

    /**
     * Handles the TLB hit/miss decision buttons.
     * Sets phase and teleports player based on their choice.
     */
    private void handleTLBDecision(Player player, boolean isHit) {
        String phase = tracker.getPhase(player);
        Journey journey = tracker.getJourney(player);

        // TLB decision is made after visiting calculator and checking VPN signs
        if (!"tlb_after_calculator".equals(phase)) {
            player.sendMessage("§cVisit the Calculator Room first, then come back to make your decision.");
            return;
        }

        boolean correctHit = journey != null && journey.isTlbHit;
        boolean correctDecision = (isHit == correctHit);

        if (correctDecision) {
            // Correct decision - proceed based on journey
            if (correctHit) {
                // TLB hit (Lucky): reveal PFN on map and go to RAM
                dialogue.speak(player, "rooms.tlb_room.after_hit_quiz_lucky", tracker.getVars(player));
                journeyMapManager.updateMap(player); // Reveal PFN from TLB entry
                tracker.setPhase(player, "ram_allow_access");
                teleportPlayer(player, "ramRoom");
            } else {
                // TLB miss: ask quiz question before opening page table door
                dialogue.speak(player, "rooms.tlb_room.after_miss_quiz_non_lucky", tracker.getVars(player));
                tracker.setPhase(player, "tlb_miss_quiz");
                // Ask the miss_door question after dialogue completes (3 second delay)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    askQuestion(player, "tlb_room.miss_door", "page_directory", "rooms.tlb_room.after_miss_correct");
                }, 60L);
            }
        } else {
            // Wrong decision - show error message
            if (correctHit) {
                // Player pressed MISS but it's actually a HIT (Lucky journey)
                player.sendMessage("§cThat's incorrect! Check the VPN signs again - your VPN IS in the TLB for this Lucky journey.");
            } else {
                // Player pressed HIT but it's actually a MISS
                player.sendMessage("§cThat's incorrect! Check the VPN signs again - your VPN is NOT displayed in the TLB.");
            }
            dialogue.speak(player, "rooms.tlb_room.incorrect_decision", tracker.getVars(player));
            // Give another chance - stay in tlb_after_calculator phase
        }
    }

    // ── Calculator Room: continue buttons ─────────────────────────────────────

    /**
     * Handles the calculator room continue button.
     * First visit (calculator_from_tlb): returns to TLB room to check VPN signs
     * Second visit (calculator_from_lazy_loading): goes to Lazy Loading room
     */
    private void handleCalculatorContinue(Player player, String buttonKey) {
        String phase = tracker.getPhase(player);
        plugin.getLogger().info("[CalcContinue] Button pressed, phase=" + phase);

        // Check if player has actually used the calculator
        if (!calculatorListener.hasPlayerCalculated(player)) {
            player.sendMessage("§c[Calculator] You must calculate your VA first! Use the hopper or press skip.");
            plugin.getLogger().info("[CalcContinue] Player hasn't calculated yet");
            return;
        }

        if ("calculator_from_tlb".equals(phase)) {
            // First visit: return to TLB room to check VPN signs and make hit/miss decision
            plugin.getLogger().info("[CalcContinue] Teleporting to TLB room");
            tracker.setPhase(player, "tlb_after_calculator");
            teleportPlayer(player, "tlbSpawn");
        } else if ("calculator_from_lazy_loading".equals(phase)) {
            // Second visit (lazy loading journey): return to Lazy Loading room
            plugin.getLogger().info("[CalcContinue] Teleporting to Lazy Loading room");
            teleportPlayer(player, "lazyLoadingRoom");
        } else {
            player.sendMessage("§cComplete the calculator room first. Current phase: " + phase);
            plugin.getLogger().warning("[CalcContinue] Wrong phase: " + phase);
        }
    }

    // ── Permission Chamber — round 1 ─────────────────────────────────────────

    private void handlePermissionDecision(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = permissionDecisionLabel(buttonKey);
        if (label == null) return;

        vars.put("button", label);
        dialogue.speak(player, "rooms.permission_chamber.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("perm_" + buttonKey));
    }

    private String permissionDecisionLabel(String key) {
        switch (key) {
            case "btn1": return "Allow Access";
            case "btn2": return "Page Fault";
            case "btn3": return "Segmentation Fault";
            case "btn4": return "Permission Fault";
            default:     return null;
        }
    }

    // ── Permission Chamber — round 2 (page fault subtype) ────────────────────

    private void handlePageFaultType(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = pageFaultTypeLabel(buttonKey);
        if (label == null) return;

        vars.put("button", label);
        dialogue.speak(player, "rooms.permission_chamber.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("pft_" + buttonKey));
    }

    private String pageFaultTypeLabel(String key) {
        switch (key) {
            case "btn1": return "Lazy Allocation";
            case "btn2": return "Lazy Loading";
            case "btn3": return "Swapping";
            // btn4 disappears in round 2 — return null to ignore press
            default:     return null;
        }
    }

    // ── Lazy Allocation Room — first visit ────────────────────────────────────

    private void handleLazyAllocDecision(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = lazyAllocDecisionLabel(buttonKey);
        if (label == null) return;

        vars.put("button", label);
        dialogue.speak(player, "rooms.lazy_allocation_room.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("la_" + buttonKey));
    }

    private String lazyAllocDecisionLabel(String key) {
        switch (key) {
            case "allocateLazy": return "ALLOCATE";
            case "swapLazy":     return "SWAP FROM DISK";
            default:             return null;
        }
    }

    // ── Lazy Allocation Room — second visit (COW choice) ─────────────────────

    private void handleLazyAllocCow(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = lazyAllocCowLabel(buttonKey);
        if (label == null) return;

        vars.put("button", label);
        dialogue.speak(player, "rooms.lazy_allocation_room.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("lac_" + buttonKey));
    }

    private String lazyAllocCowLabel(String key) {
        switch (key) {
            case "cowLazyAlloc": return "COW (COPY-ON-WRITE)";
            case "btnLazyAlloc": return "DENY THE WRITE";
            case "nothing":      return "DO NOTHING";
            default:             return null;
        }
    }

    // ── COW Room ─────────────────────────────────────────────────────────────

    private void handleCowDecision(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = cowDecisionLabel(buttonKey);
        if (label == null) return;

        vars.put("button", label);
        dialogue.speak(player, "rooms.cow_room.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("cow_" + buttonKey));
    }

    private String cowDecisionLabel(String key) {
        switch (key) {
            case "allocateCow": return "ALLOCATE & COPY";
            case "terminate":   return "TERMINATE PROCESS";
            default:            return null;
        }
    }

    // ── Resolve confirmed choices ─────────────────────────────────────────────

    private void resolveConfirmedChoice(Player player, String buttonKey) {
        Journey journey = tracker.getJourney(player);
        Map<String, String> vars = tracker.getVars(player);

        if (buttonKey.startsWith("perm_")) {
            resolvePermissionDecision(player, buttonKey.substring(5), journey, vars);
        } else if (buttonKey.startsWith("pft_")) {
            resolvePageFaultType(player, buttonKey.substring(4), journey, vars);
        } else if (buttonKey.startsWith("la_")) {
            resolveLazyAllocDecision(player, buttonKey.substring(3), journey, vars);
        } else if (buttonKey.startsWith("lac_")) {
            resolveLazyAllocCow(player, buttonKey.substring(4), vars);
        } else if (buttonKey.startsWith("cow_")) {
            resolveCowDecision(player, buttonKey.substring(4), vars);
        }
    }

    private void resolvePermissionDecision(Player player, String action, Journey journey, Map<String, String> vars) {
        String answer = buttonToPermissionAnswer(action);
        boolean correct = answer.equals(journey.permissionAnswer);

        if (!correct) {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "permission_chamber");
            dialogue.speakLine(player, permissionIncorrectFeedback(action), vars);
            return;
        }

        switch (answer) {
            case "allow_access":
                dialogue.speak(player, "feedback.allow_access_correct", vars);
                tracker.setPhase(player, "ram_allow_access");
                clearPermChoiceSigns();
                updateSign("perChamber.sign6", "Go to RAM", "", "", "");
                break;
            case "page_fault":
                dialogue.speak(player, "feedback.page_fault_correct", vars);
                tracker.setPhase(player, "page_fault_type");
                // Switch signs to page-fault subtype labels (sign4 disappears)
                updateSign("perChamber.sign1", "Lazy Allocation", "", "", "");
                updateSign("perChamber.sign2", "Lazy Loading", "", "", "");
                updateSign("perChamber.sign3", "Swapping", "", "", "");
                updateSign("perChamber.sign4", "", "", "", "");
                updateSign("perChamber.sign5", "Which type of", "page fault?", "", "");
                dialogue.speak(player, "rooms.permission_chamber.page_fault_subtype_prompt", vars);
                break;
            case "segfault":
                dialogue.speak(player, "feedback.segfault_correct", vars);
                tracker.setPhase(player, "segfault_end");
                progress.markComplete(player, Journey.PERMISSION_VIOLATION);
                clearPermChoiceSigns();
                updateSign("perChamber.sign6", "Terminate", "process and", "finish", "");
                break;
            case "protection_fault":
                dialogue.speak(player, "feedback.protection_fault_correct", vars);
                tracker.setPhase(player, "cow_decision");
                clearPermChoiceSigns();
                updateSign("perChamber.sign6", "Go to COW", "room", "", "");
                break;
        }
    }

    /** Map btn1-4 → internal permission answer keys matching Journey.permissionAnswer. */
    private String buttonToPermissionAnswer(String btnKey) {
        switch (btnKey) {
            case "btn1": return "allow_access";
            case "btn2": return "page_fault";
            case "btn3": return "segfault";
            case "btn4": return "protection_fault";
            default:     return btnKey;
        }
    }

    private String permissionIncorrectFeedback(String action) {
        switch (action) {
            case "btn1": return "No. Check the PRESENT bit and permission flags again.";
            case "btn2": return "No. The PRESENT bit is 1. This page is already in memory.";
            case "btn3": return "No. Check if this is truly an out-of-bounds access.";
            case "btn4": return "No. Check the COW bit and what operation is requested.";
            default:     return "That's not correct. Try again.";
        }
    }

    private void resolvePageFaultType(Player player, String action, Journey journey, Map<String, String> vars) {
        String selectedType = buttonToPageFaultType(action);
        String expected = journey.pageFaultType;
        boolean correct = selectedType.equals(expected);

        if (!correct) {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "permission_chamber_pft");
            player.sendMessage("§c" + pageFaultTypeIncorrectFeedback(action));
            return;
        }

        switch (expected) {
            case "lazy_allocation":
                dialogue.speak(player, "feedback.lazy_allocation_correct", vars);
                dialogue.speak(player, "rooms.permission_chamber.proceed_to_corridor", vars);
                tracker.setPhase(player, "lazy_alloc_decision");
                clearPermSubtypeSigns();
                break;
            case "lazy_loading":
                dialogue.speak(player, "feedback.lazy_loading_correct", vars);
                dialogue.speak(player, "rooms.permission_chamber.proceed_to_corridor", vars);
                tracker.setPhase(player, "lazy_loading_entered");
                clearPermSubtypeSigns();
                break;
            case "swapped_out":
                dialogue.speak(player, "feedback.swapped_out_correct", vars);
                dialogue.speak(player, "rooms.permission_chamber.proceed_to_disk", vars);
                tracker.setPhase(player, "disk_swap_retrieval");
                clearPermSubtypeSigns();
                updateSign("perChamber.sign6", "Go to Disk", "", "", "");
                break;
        }
    }

    /** Map btn1-3 → page fault type keys matching Journey.pageFaultType. */
    private String buttonToPageFaultType(String btnKey) {
        switch (btnKey) {
            case "btn1": return "lazy_allocation";
            case "btn2": return "lazy_loading";
            case "btn3": return "swapped_out";
            default:     return "";
        }
    }

    private String pageFaultTypeIncorrectFeedback(String action) {
        switch (action) {
            case "btn1": return "Check the ANON bit. Is it truly anonymous?";
            case "btn2": return "Check the FILE_BACKED bit. Is it truly file-backed?";
            case "btn3": return "Check the IN_SWAP bit. Look at your PTE again.";
            default:     return "That's not correct. Try again.";
        }
    }

    private void resolveLazyAllocDecision(Player player, String action, Journey journey, Map<String, String> vars) {
        if ("allocateLazy".equals(action)) {
            dialogue.speak(player, "rooms.lazy_allocation_room.allocate_correct", vars);
            tracker.setPhase(player, "ram_after_lazy_alloc");
            // Hide choice signs; show "Go to RAM" on mixSign (btnLazyAlloc at z:54 now TPs)
            updateSign("lazyAllocation.allocateSign", "", "", "", "");
            updateSign("lazyAllocation.swapSign", "", "", "", "");
            updateSign("lazyAllocation.mixSign", "Go to RAM", "", "", "");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "lazy_allocation_room");
            dialogue.speak(player, "rooms.lazy_allocation_room.allocate_incorrect", vars);
        }
    }

    private void resolveLazyAllocCow(Player player, String action, Map<String, String> vars) {
        if ("cowLazyAlloc".equals(action)) {
            dialogue.speak(player, "rooms.lazy_allocation_room.second_visit_correct", vars);
            tracker.setPhase(player, "going_to_cow");
            // Hide COW-decision signs; show "Go to COW room" (btnLazyAlloc now TPs to COW)
            updateSign("lazyAllocation.cowSign", "", "", "", "");
            updateSign("lazyAllocation.doNothingSign", "", "", "", "");
            updateSign("lazyAllocation.writeSign", "", "", "", "");
            updateSign("lazyAllocation.mixSign", "Go to COW room", "", "", "");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "lazy_allocation_cow");
            dialogue.speak(player, "rooms.lazy_allocation_room.second_visit_incorrect", vars);
        }
    }

    private void resolveCowDecision(Player player, String action, Map<String, String> vars) {
        if ("allocateCow".equals(action)) {
            // Advance pfn to the new private frame (pfnCow set at journey start)
            String pfnCow = tracker.getVar(player, "pfnCow");
            if (!"?".equals(pfnCow)) {
                tracker.setVar(player, "pfn", pfnCow);
                vars.put("pfn", pfnCow);  // update the local ref too so dialogue sees it immediately
            }
            // Update PTE: set WRITE=1 for the private copy
            tracker.setVar(player, "pteWrite", "1");
            tracker.setVar(player, "pteReadOnly", "0");

            dialogue.speak(player, "rooms.cow_room.allocate_copy_correct", vars);
            tracker.setPhase(player, "ram_after_cow");
            // Reveal the "Go to RAM" sign in the COW room
            updateSign("cow.toRam", "Go to RAM", "", "", "");
            
            // Update the PTE map with new PFN and WRITE values
            pageTableManager.updatePteMapAfterCow(player);
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "cow_room");
            dialogue.speak(player, "rooms.cow_room.terminate_incorrect", vars);
        }
    }

    // ── Journey selection (adventurer mode) ───────────────────────────────────

    /** Show the journey list — all journeys are unlocked in Adventurer Mode. */
    public void showJourneyList(Player player) {
        player.sendMessage("§6════════════════════════════════");
        player.sendMessage("§b§lChoose Your Journey");
        player.sendMessage("§7All journeys available. Choose your challenge.");
        player.sendMessage("");
        for (Journey j : Journey.values()) {
            boolean done = progress.isComplete(player, j);
            String prefix = done ? "§a[✓] " : "§e[→] ";
            String name   = done ? "§7" + j.displayName : "§f" + j.displayName;
            player.sendMessage("  §f" + j.number + ". " + prefix + name);
        }
        player.sendMessage("");
        player.sendMessage("§7Type §f1§7–§f7 §7to choose, or §f8 §7for random.");
        player.sendMessage("§6════════════════════════════════");
    }

    private void handleJourneySelection(Player player, String input) {
        int choice;
        try {
            choice = Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            player.sendMessage("§cPlease type a number between 1 and 8.");
            return;
        }

        Journey selected;
        if (choice == 8) {
            List<Journey> unlocked = new ArrayList<>();
            for (Journey j : Journey.values()) {
                if (progress.isUnlocked(player, j)) unlocked.add(j);
            }
            selected = unlocked.get((int) (Math.random() * unlocked.size()));
            tracker.setJourney(player, selected);
            dialogue.speak(player, "rooms.terminal.journey_random", tracker.getVars(player));
        } else {
            selected = Journey.fromNumber(choice);
            if (selected == null) {
                player.sendMessage("§cInvalid choice. Type 1–7 to select a journey or 8 for random.");
                return;
            }
            tracker.setJourney(player, selected);
            dialogue.speak(player, "rooms.terminal.journey_selected", tracker.getVars(player));
        }
        tracker.setPhase(player, "terminal_journey_chosen");
        journeyMapManager.giveInitialMap(player, "adventurerChest");
    }

    // ── Public sign update methods (called by RoomChangeListener) ─────────────

    /** Set the RAM room mixSign text. */
    public void setRamMixSign(String l1, String l2, String l3, String l4) {
        updateSign("ramRoom.mixSign", l1, l2, l3, l4);
    }

    /** Clear round-1 choice signs (1-5) after a correct round-1 decision. */
    private void clearPermChoiceSigns() {
        updateSign("perChamber.sign1", "", "", "", "");
        updateSign("perChamber.sign2", "", "", "", "");
        updateSign("perChamber.sign3", "", "", "", "");
        updateSign("perChamber.sign4", "", "", "", "");
        updateSign("perChamber.sign5", "", "", "", "");
    }

    /** Clear round-2 subtype signs (1-3, 5) after a correct page fault type decision. */
    private void clearPermSubtypeSigns() {
        updateSign("perChamber.sign1", "", "", "", "");
        updateSign("perChamber.sign2", "", "", "", "");
        updateSign("perChamber.sign3", "", "", "", "");
        updateSign("perChamber.sign5", "", "", "", "");
    }

    /** Initialise all Permission Chamber signs to their default (round-1) state. */
    public void initPermissionChamberSigns() {
        updateSign("perChamber.sign1", "Allow Access", "", "", "");
        updateSign("perChamber.sign2", "Page Fault", "", "", "");
        updateSign("perChamber.sign3", "Segmentation", "Fault", "", "");
        updateSign("perChamber.sign4", "Permission", "Fault", "", "");
        updateSign("perChamber.sign5", "Make Your", "Decision", "", "");
        updateSign("perChamber.sign6", "", "", "", "");
    }

    /** Clear the COW room "Go to RAM" sign (called on room entry). */
    public void clearCowToRamSign() {
        updateSign("cow.toRam", "", "", "", "");
    }

    /** Show Lazy Allocation Room first-visit signs (Allocate / Swap from Disk). */
    public void setLazyAllocDecisionSigns() {
        updateSign("lazyAllocation.allocateSign", "Allocate", "", "", "");
        updateSign("lazyAllocation.swapSign", "Swap from", "Disk", "", "");
        updateSign("lazyAllocation.mixSign", "", "", "", "");
        updateSign("lazyAllocation.cowSign", "", "", "", "");
        updateSign("lazyAllocation.doNothingSign", "", "", "", "");
        updateSign("lazyAllocation.writeSign", "", "", "", "");
    }

    /** Show Lazy Allocation Room second-visit signs (COW / Deny / Do nothing). */
    public void setLazyAllocCowSigns() {
        updateSign("lazyAllocation.allocateSign", "", "", "", "");
        updateSign("lazyAllocation.swapSign", "", "", "", "");
        updateSign("lazyAllocation.mixSign", "Deny the write", "", "", "");
        updateSign("lazyAllocation.cowSign", "Do COW", "", "", "");
        updateSign("lazyAllocation.doNothingSign", "Do nothing", "", "", "");
        updateSign("lazyAllocation.writeSign", "Process wants", "to write. What", "do you do?", "");
    }

    /** Set the Lazy Loading Room mixSign (Go to Calculator Room / Go to Disk). */
    public void setLoadingSign(String l1, String l2, String l3, String l4) {
        updateSign("lazyLoading.mixSign", l1, l2, l3, l4);
    }

    // ── Quiz helpers ──────────────────────────────────────────────────────────

    public void askQuestion(Player player, String questionPath,
                            String onCorrectPhase, String onCorrectDialoguePath) {
        QuestionBank.Question q = questionBank.getQuestion(questionPath);
        if (q == null) return;
        sendQuestion(player, q);
        pendingQuiz.put(player.getUniqueId(),
            new PendingQuiz(questionPath, onCorrectPhase, onCorrectDialoguePath));
    }

    private void sendConfirmQuestion(Player player) {
        QuestionBank.Question q = questionBank.getQuestion("general.confirm_choice");
        if (q != null) {
            player.sendMessage("§6[Kernel Guardian] §e" + q.text);
        }
    }

    private void sendQuestion(Player player, QuestionBank.Question q) {
        Map<String, String> vars = tracker.getVars(player);
        String questionText = q.text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            questionText = questionText.replace("{" + e.getKey() + "}", e.getValue());
        }
        player.sendMessage("§6[Quiz] §e" + questionText);
        if (q.isMultipleChoice()) {
            player.sendMessage(q.formatOptions(vars));
        }
    }

    // ── Sign + TP utilities ───────────────────────────────────────────────────

    /**
     * Update the text on a wall sign at the location given by config path
     * {@code signs.<configPath>}. Silently warns if the sign block isn't placed yet.
     */
    private void updateSign(String configPath, String l1, String l2, String l3, String l4) {
        ConfigurationSection section =
            plugin.getConfig().getConfigurationSection("signs." + configPath);
        if (section == null) {
            plugin.getLogger().warning("[Signs] No config entry for signs." + configPath);
            return;
        }
        String worldName = section.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(world,
            section.getInt("x"), section.getInt("y"), section.getInt("z"));
        Block block = loc.getBlock();

        if (!(block.getState() instanceof Sign sign)) {
            plugin.getLogger().warning("[Signs] No sign block at signs." + configPath
                + " (" + block.getType() + " at "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")");
            return;
        }

        sign.getSide(Side.FRONT).line(0, Component.text(l1));
        sign.getSide(Side.FRONT).line(1, Component.text(l2));
        sign.getSide(Side.FRONT).line(2, Component.text(l3));
        sign.getSide(Side.FRONT).line(3, Component.text(l4));
        sign.update(true);
    }

    /**
     * Opens the door block(s) at the location given by config path {@code doors.<doorKey>}.
     * Sets both the bottom and top halves open so the player can walk through.
     */
    private void openDoor(String doorKey) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("doors." + doorKey);
        if (sec == null) {
            plugin.getLogger().warning("[DoorOpen] No config for doors." + doorKey);
            return;
        }
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Block bottom = world.getBlockAt(sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        if (bottom.getBlockData() instanceof Openable openable) {
            openable.setOpen(true);
            bottom.setBlockData(openable);
            // Top half of the door
            Block top = world.getBlockAt(sec.getInt("x"), sec.getInt("y") + 1, sec.getInt("z"));
            if (top.getBlockData() instanceof Openable topOpenable) {
                topOpenable.setOpen(true);
                top.setBlockData(topOpenable);
            }
            plugin.getLogger().info("[DoorOpen] Opened door: " + doorKey);
        } else {
            plugin.getLogger().warning("[DoorOpen] Block at doors." + doorKey + " is not a door (" + bottom.getType() + ")");
        }
    }

    /** Closes the door block(s) at the location given by config path {@code doors.<doorKey>}. */
    public void closeDoor(String doorKey) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("doors." + doorKey);
        if (sec == null) return;
        String worldName = sec.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Block bottom = world.getBlockAt(sec.getInt("x"), sec.getInt("y"), sec.getInt("z"));
        if (bottom.getBlockData() instanceof Openable openable) {
            openable.setOpen(false);
            bottom.setBlockData(openable);
            Block top = world.getBlockAt(sec.getInt("x"), sec.getInt("y") + 1, sec.getInt("z"));
            if (top.getBlockData() instanceof Openable topOpenable) {
                topOpenable.setOpen(false);
                top.setBlockData(topOpenable);
            }
        }
    }

    // ── Pressure plate handler ────────────────────────────────────────────────

    /**
     * Handles pressure plate activations (Action.PHYSICAL) for door-open plates.
     * Checks journey/phase conditions before opening the corresponding door.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPressurePlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        if (event.getClickedBlock() == null) return;
        String typeName = event.getClickedBlock().getType().name();
        if (!typeName.endsWith("_PRESSURE_PLATE")) return;

        Location clicked = event.getClickedBlock().getLocation();
        String key = findButton(clicked);
        if (key == null) return;

        Player player = event.getPlayer();
        String phase = tracker.getPhase(player);
        Journey journey = tracker.getJourney(player);

        switch (key) {
            case "toPageFaultCorridor":
                if (journey == Journey.LAZY_ALLOCATION && "lazy_alloc_decision".equals(phase)) {
                    openDoor("toPageFaultCorridor");
                } else if (journey == Journey.LAZY_LOADING && "lazy_loading_entered".equals(phase)) {
                    openDoor("toPageFaultCorridor");
                } else {
                    // Wrong journey - door should not open
                    player.sendMessage("§c[Permission Chamber] This door is not for your current journey!");
                }
                break;

            case "toLazyLoading":
                if (journey == Journey.LAZY_LOADING) {
                    openDoor("toLazyLoading");
                } else {
                    player.sendMessage("§c[Permission Chamber] This door is not for your current journey!");
                }
                break;

            case "toLazyAllocation":
                if (journey == Journey.LAZY_ALLOCATION) {
                    openDoor("toLazyAllocation");
                } else {
                    player.sendMessage("§c[Permission Chamber] This door is not for your current journey!");
                }
                break;

            // toSwap: not used yet
            default:
                break;
        }
    }

    /** Teleport a player to a named location from LocationRegistry. */
    private void teleportPlayer(Player player, String destination) {
        plugin.getLogger().info("[CalcContinue] Teleporting to: " + destination);
        Location dest = locationRegistry.get(destination);
        if (dest == null) {
            plugin.getLogger().warning("[CalcContinue] TP destination not found: " + destination);
            player.sendMessage("§c[Error] Destination '" + destination + "' not found!");
            return;
        }
        plugin.getLogger().info("[CalcContinue] Destination found at: " + dest.getBlockX() + "," + dest.getBlockY() + "," + dest.getBlockZ());
        player.teleport(dest);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Parse VPN hex string (e.g. "0x7") to integer value. */
    private int parseVpnHex(String vpnHex) {
        try {
            return Integer.parseInt(vpnHex.replace("0x", "").replace("0X", ""), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String findButton(Location clicked) {
        for (Map.Entry<Location, String> entry : buttons.entrySet()) {
            Location loc = entry.getKey();
            if (loc.getWorld() != null && loc.getWorld().equals(clicked.getWorld())
                    && loc.getBlockX() == clicked.getBlockX()
                    && loc.getBlockY() == clicked.getBlockY()
                    && loc.getBlockZ() == clicked.getBlockZ()) {
                return entry.getValue();
            }
        }
        return null;
    }
}
