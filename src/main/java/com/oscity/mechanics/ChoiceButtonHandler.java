package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.content.QuestionBank;
import com.oscity.gamification.ProgressTracker;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.journey.Journey;
import com.oscity.journey.JourneyManager;
import com.oscity.session.JourneyTracker;
import com.oscity.world.LocationRegistry;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
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

    // Map: button location → config key name
    private final Map<Location, String> buttons = new HashMap<>();

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
                               CalculatorListener calculatorListener) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.dialogue = dialogue;
        this.questionBank = questionBank;
        this.progress = progress;
        this.locationRegistry = locationRegistry;
        this.calculatorListener = calculatorListener;
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
        registerFromTpButtons("cowToRam", "cowToRam");
        registerFromTpButtons("loadingToCalc", "loadingTp");  // loadingToDisk shares same location

        plugin.getLogger().info("ChoiceButtonHandler: loaded " + buttons.size() + " choice/smart buttons.");
    }

    private void registerFromTpButtons(String tpKey, String smartKey) {
        ConfigurationSection btn = plugin.getConfig().getConfigurationSection("tpButtons." + tpKey);
        if (btn == null) return;
        String worldName = btn.getString("world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        Location loc = new Location(world, btn.getInt("x"), btn.getInt("y"), btn.getInt("z"));
        buttons.put(loc, smartKey);
    }

    // ── Button press ─────────────────────────────────────────────────────────

    /**
     * LOW priority so this fires before TeleportManager (NORMAL).
     * Cancels the event for any button in our map, preventing double-handling.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onButtonPress(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        if (!event.getClickedBlock().getType().name().endsWith("_BUTTON")) return;

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

        if ("skipCalc".equals(buttonKey)) {
            calculatorListener.skipCalculation(player);
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
            default:
                break;
        }
    }

    // ── Chat listener (YES/NO confirms and quiz answers) ─────────────────────

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
                    // RETRY INSTRUCTION → FINISH (Pure COW)
                    tracker.setPhase(player, "ram_finish");
                    updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                }
                break;

            case "disk_swap_retrieval":
                // SWAPPED_OUT from disk: RETRY INSTRUCTION → FINISH
                tracker.setPhase(player, "ram_finish");
                updateSign("ramRoom.mixSign", "FINISH", "", "", "");
                break;

            case "disk_lazy_loading":
                // LAZY_LOADING from disk: CONTINUE → TP to Swap District
                teleportPlayer(player, "swapDistrict");
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
                if (journey != null && !progress.isComplete(player, journey)) {
                    progress.markComplete(player, journey);
                }
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

            default:
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
                updateSign("perChamber.sign6", "Go to RAM", "", "", "");
                break;
            case "page_fault":
                dialogue.speak(player, "feedback.page_fault_correct", vars);
                tracker.setPhase(player, "page_fault_type");
                // Switch signs to page-fault subtype labels
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
                updateSign("perChamber.sign6", "Terminate", "process and", "finish", "");
                break;
            case "protection_fault":
                dialogue.speak(player, "feedback.protection_fault_correct", vars);
                tracker.setPhase(player, "cow_decision");
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
                break;
            case "lazy_loading":
                dialogue.speak(player, "feedback.lazy_loading_correct", vars);
                dialogue.speak(player, "rooms.permission_chamber.proceed_to_corridor", vars);
                tracker.setPhase(player, "lazy_loading_entered");
                break;
            case "swapped_out":
                dialogue.speak(player, "feedback.swapped_out_correct", vars);
                dialogue.speak(player, "rooms.permission_chamber.proceed_to_disk", vars);
                tracker.setPhase(player, "disk_swap_retrieval");
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
            dialogue.speak(player, "rooms.cow_room.allocate_copy_correct", vars);
            tracker.setPhase(player, "ram_after_cow");
            // Reveal the "Go to RAM" sign in the COW room
            updateSign("cow.toRam", "Go to RAM", "", "", "");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "cow_room");
            dialogue.speak(player, "rooms.cow_room.terminate_incorrect", vars);
        }
    }

    // ── Journey selection (adventurer mode) ───────────────────────────────────

    /** Show the ordered journey list with completion/lock indicators. */
    public void showJourneyList(Player player) {
        player.sendMessage("§6════════════════════════════════");
        player.sendMessage("§b§lChoose Your Journey");
        player.sendMessage("§7Complete them in order to unlock the next.");
        player.sendMessage("");
        for (Journey j : Journey.values()) {
            boolean done     = progress.isComplete(player, j);
            boolean unlocked = progress.isUnlocked(player, j);
            String prefix = done     ? "§a[✓] " :
                            unlocked ? "§e[→] " :
                                       "§8[🔒] ";
            String name = done     ? "§7" + j.displayName :
                          unlocked ? "§f" + j.displayName :
                                     "§8" + j.displayName;
            player.sendMessage("  §f" + j.number + ". " + prefix + name);
        }
        player.sendMessage("");
        player.sendMessage("§7Type §f1§7–§f7 §7to choose, or §f8 §7for random (unlocked only).");
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
            if (!progress.isUnlocked(player, selected)) {
                Journey prev = Journey.fromNumber(selected.number - 1);
                player.sendMessage("§c🔒 Journey " + choice + " is locked. Complete \""
                    + (prev != null ? prev.displayName : "the previous journey")
                    + "\" first.");
                return;
            }
            tracker.setJourney(player, selected);
            dialogue.speak(player, "rooms.terminal.journey_selected", tracker.getVars(player));
        }
        tracker.setPhase(player, "terminal_journey_chosen");
    }

    // ── Public sign update methods (called by RoomChangeListener) ─────────────

    /** Set the RAM room mixSign text. */
    public void setRamMixSign(String l1, String l2, String l3, String l4) {
        updateSign("ramRoom.mixSign", l1, l2, l3, l4);
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

    /** Teleport a player to a named location from LocationRegistry. */
    private void teleportPlayer(Player player, String destination) {
        Location dest = locationRegistry.get(destination);
        if (dest == null) {
            plugin.getLogger().warning("[ChoiceButtonHandler] TP destination not found: " + destination);
            return;
        }
        player.teleport(dest);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

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
