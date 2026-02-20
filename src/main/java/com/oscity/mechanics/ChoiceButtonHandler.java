package com.oscity.mechanics;

import com.oscity.content.DialogueManager;
import com.oscity.content.QuestionBank;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.session.Journey;
import com.oscity.session.JourneyTracker;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all choice button presses and subsequent chat-based quiz answers.
 *
 * Button resolution:
 *   Each button has a config key (e.g. "allowAccess"). When a button is pressed,
 *   the logical action depends on the player's current phase from JourneyTracker.
 *
 * Phase → button group mapping (permission chamber reuses same 4 positions):
 *   "permission_decision"  → allowAccess / pageFault / segFaultPerChamber / perFault
 *   "page_fault_type"      → lazyLoading / lazyAllocation / cowPerChamber / swap
 *   "lazy_alloc_decision"  → allocate / terminate
 *   "lazy_alloc_cow"       → cowLazyAlloc / segFaultPerLazyAlloc / nothing
 *   "cow_decision"         → (allocate / terminate reused)
 *
 * After a choice button is pressed the guardian asks "Is this your final answer?
 * Type YES or NO" and then waits for chat input.
 */
public class ChoiceButtonHandler implements Listener {

    private final JavaPlugin plugin;
    private final JourneyTracker tracker;
    private final DialogueManager dialogue;
    private final QuestionBank questionBank;

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
                               DialogueManager dialogue, QuestionBank questionBank) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.dialogue = dialogue;
        this.questionBank = questionBank;
        loadButtons();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("ChoiceButtonHandler registered.");
    }

    // ── Button loading ────────────────────────────────────────────────────────

    private void loadButtons() {
        buttons.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("choiceButtons");
        if (sec == null) {
            plugin.getLogger().warning("ChoiceButtonHandler: no 'choiceButtons' in config.yml");
            return;
        }
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
        plugin.getLogger().info("ChoiceButtonHandler: loaded " + buttons.size() + " choice buttons.");
    }

    // ── Button press ─────────────────────────────────────────────────────────

    @EventHandler
    public void onButtonPress(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        if (!event.getClickedBlock().getType().name().endsWith("_BUTTON")) return;

        Location clicked = event.getClickedBlock().getLocation();
        String buttonKey = findButton(clicked);
        if (buttonKey == null) return;

        Player player = event.getPlayer();
        String phase = tracker.getPhase(player);
        Journey journey = tracker.getJourney(player);
        Map<String, String> vars = tracker.getVars(player);

        // Dispatch by current phase
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
            default:
                // Button pressed but not in an active decision phase — ignore silently
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
                // Invalid input — re-prompt without consuming the pending state
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
                    // Resend question so player can try again
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

    // ── Permission Chamber — round 1 ─────────────────────────────────────────

    private void handlePermissionDecision(Player player, String buttonKey, Journey journey, Map<String, String> vars) {
        String label = permissionDecisionLabel(buttonKey);
        if (label == null) return; // wrong button group for this phase

        vars.put("button", label);
        dialogue.speak(player, "rooms.permission_chamber.after_button_press", vars);
        sendConfirmQuestion(player);
        pendingConfirm.put(player.getUniqueId(), new PendingChoice("perm_" + buttonKey));
    }

    private String permissionDecisionLabel(String key) {
        switch (key) {
            case "allowAccess":        return "ALLOW ACCESS";
            case "pageFault":          return "PAGE FAULT";
            case "segFaultPerChamber": return "SEGMENTATION FAULT";
            case "perFault":           return "PROTECTION FAULT";
            default:                   return null;
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
            case "lazyLoading":    return "LAZY LOADING";
            case "lazyAllocation": return "LAZY ALLOCATION";
            case "cowPerChamber":  return "COW (Protection Fault)";
            case "swap":           return "SWAPPED-OUT PAGE";
            default:               return null;
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
            case "allocate":  return "ALLOCATE";
            case "terminate": return "SWAP FROM DISK";
            default:          return null;
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
            case "cowLazyAlloc":         return "COW (COPY-ON-WRITE)";
            case "segFaultPerLazyAlloc": return "SEGMENTATION FAULT";
            case "nothing":              return "DO NOTHING";
            default:                     return null;
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
            case "allocate":  return "ALLOCATE & COPY";
            case "terminate": return "TERMINATE PROCESS";
            default:          return null;
        }
    }

    // ── Resolve confirmed choices ─────────────────────────────────────────────

    private void resolveConfirmedChoice(Player player, String buttonKey) {
        Journey journey = tracker.getJourney(player);
        Map<String, String> vars = tracker.getVars(player);

        // Permission Chamber — round 1
        if (buttonKey.startsWith("perm_")) {
            String action = buttonKey.substring(5); // strip "perm_"
            resolvePermissionDecision(player, action, journey, vars);
            return;
        }

        // Permission Chamber — round 2
        if (buttonKey.startsWith("pft_")) {
            String action = buttonKey.substring(4);
            resolvePageFaultType(player, action, journey, vars);
            return;
        }

        // Lazy Allocation — first visit
        if (buttonKey.startsWith("la_")) {
            String action = buttonKey.substring(3);
            resolveLazyAllocDecision(player, action, journey, vars);
            return;
        }

        // Lazy Allocation — second visit (COW)
        if (buttonKey.startsWith("lac_")) {
            String action = buttonKey.substring(4);
            resolveLazyAllocCow(player, action, vars);
            return;
        }

        // COW Room
        if (buttonKey.startsWith("cow_")) {
            String action = buttonKey.substring(4);
            resolveCowDecision(player, action, vars);
        }
    }

    private void resolvePermissionDecision(Player player, String action, Journey journey, Map<String, String> vars) {
        boolean correct = action.equals(journey.permissionAnswer);

        if (!correct) {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "permission_chamber");
            dialogue.speakLine(player, permissionIncorrectFeedback(action), vars);
            return;
        }

        // Correct — dispatch by which answer was chosen
        switch (action) {
            case "allow_access":
                dialogue.speak(player, "feedback.allow_access_correct", vars);
                tracker.setPhase(player, "ram_allow_access");
                break;
            case "page_fault":
                dialogue.speak(player, "feedback.page_fault_correct", vars);
                // Advance to subtype selection
                tracker.setPhase(player, "page_fault_type");
                dialogue.speak(player, "rooms.permission_chamber.page_fault_subtype_prompt", vars);
                break;
            case "segfault":
                dialogue.speak(player, "feedback.segfault_correct", vars);
                tracker.setPhase(player, "segfault_end");
                break;
            case "protection_fault":
                dialogue.speak(player, "feedback.protection_fault_correct", vars);
                tracker.setPhase(player, "cow_decision");
                break;
        }
    }

    private String permissionIncorrectFeedback(String action) {
        switch (action) {
            case "allowAccess": return "No. Check the PRESENT bit and permission flags again.";
            case "pageFault":   return "No. The PRESENT bit is 1. This page is already in memory.";
            case "segFaultPerChamber": return "No. Check if this is truly an out-of-bounds access.";
            case "perFault":    return "No. Check the COW bit and what operation is requested.";
            default:            return "That's not correct. Try again.";
        }
    }

    private void resolvePageFaultType(Player player, String action, Journey journey, Map<String, String> vars) {
        String expected = journey.pageFaultType; // "lazy_allocation", "lazy_loading", "swapped_out"
        boolean correct = actionToPageFaultKey(action).equals(expected);

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
                break;
        }
    }

    private String actionToPageFaultKey(String action) {
        switch (action) {
            case "lazyLoading":    return "lazy_loading";
            case "lazyAllocation": return "lazy_allocation";
            case "cowPerChamber":  return "swapped_out"; // Protection fault → COW
            case "swap":           return "swapped_out";
            default:               return "";
        }
    }

    private String pageFaultTypeIncorrectFeedback(String action) {
        switch (action) {
            case "lazyLoading":    return "Check the FILE_BACKED bit again. Or check if the page is anonymous or in swap space.";
            case "lazyAllocation": return "Check the ANON bit again. Or check if the page is file-backed or in swap space.";
            case "cowPerChamber":  return "Check the IN_SWAP bit again. Or check if the page is anonymous or file-backed.";
            case "swap":           return "Check the IN_SWAP bit. Look again at your PTE.";
            default:               return "That's not correct. Try again.";
        }
    }

    private void resolveLazyAllocDecision(Player player, String action, Journey journey, Map<String, String> vars) {
        if ("allocate".equals(action)) {
            dialogue.speak(player, "rooms.lazy_allocation_room.allocate_correct", vars);
            tracker.setPhase(player, "ram_after_lazy_alloc");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "lazy_allocation_room");
            dialogue.speak(player, "rooms.lazy_allocation_room.allocate_incorrect", vars);
        }
    }

    private void resolveLazyAllocCow(Player player, String action, Map<String, String> vars) {
        if ("cowLazyAlloc".equals(action)) {
            dialogue.speak(player, "rooms.lazy_allocation_room.second_visit_correct", vars);
            tracker.setPhase(player, "cow_decision");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "lazy_allocation_cow");
            dialogue.speak(player, "rooms.lazy_allocation_room.second_visit_incorrect", vars);
        }
    }

    private void resolveCowDecision(Player player, String action, Map<String, String> vars) {
        if ("allocate".equals(action)) {
            dialogue.speak(player, "rooms.cow_room.allocate_copy_correct", vars);
            tracker.setPhase(player, "ram_after_cow");
        } else {
            SQLiteStudyDatabase.logWrongAnswer(vars.getOrDefault("sessionId", "?"), "cow_room");
            dialogue.speak(player, "rooms.cow_room.terminate_incorrect", vars);
        }
    }

    // ── Journey selection (adventurer mode) ───────────────────────────────────

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
            selected = Journey.random();
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
    }

    // ── Quiz helpers ──────────────────────────────────────────────────────────

    /**
     * Present a question to a player and register the pending quiz state.
     * When answered correctly, the player's phase advances to onCorrectPhase
     * and onCorrectDialoguePath is spoken.
     */
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
