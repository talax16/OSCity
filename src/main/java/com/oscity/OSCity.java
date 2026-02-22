package com.oscity;

import com.oscity.config.ConfigManager;
import com.oscity.content.DialogueManager;
import com.oscity.content.QuestionBank;
import com.oscity.core.GuardianInteractionHandler;
import com.oscity.core.KernelGuardian;
import com.oscity.core.RoomChangeListener;
import com.oscity.gamification.ProgressTracker;
import com.oscity.mechanics.CalculatorListener;
import com.oscity.mechanics.ChoiceButtonHandler;
import com.oscity.mechanics.HintSystem;
import com.oscity.mechanics.RoomDisplayManager;
import com.oscity.mechanics.JourneyMapManager;
import com.oscity.mechanics.SwapClockManager;
import com.oscity.mechanics.TeleportManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import com.oscity.quiz.QuizManager;
import com.oscity.session.JourneyTracker;
import com.oscity.session.SessionManager;
import com.oscity.world.LocationRegistry;
import com.oscity.world.RoomRegistry;
import com.oscity.world.StructureManager;
import com.oscity.world.WorldManager;

import org.bukkit.plugin.java.JavaPlugin;

public class OSCity extends JavaPlugin {

    private ConfigManager configManager;
    private WorldManager worldManager;
    private RoomRegistry roomRegistry;
    private StructureManager structureManager;
    private LocationRegistry locationRegistry;
    private RoomDisplayManager roomDisplayManager;
    private TeleportManager teleportManager;
    private KernelGuardian kernelGuardian;
    private GuardianInteractionHandler guardianHandler;
    private RoomChangeListener roomChangeListener;

    // Content
    private DialogueManager dialogueManager;
    private QuestionBank questionBank;

    // Session & journey
    private SessionManager sessionManager;
    private JourneyTracker journeyTracker;
    private ProgressTracker progressTracker;

    // Game systems
    private HintSystem hintSystem;
    private QuizManager quizManager;
    private SwapClockManager swapClockManager;
    private JourneyMapManager journeyMapManager;
    private ChoiceButtonHandler choiceButtonHandler;
    private CalculatorListener calculatorListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        // Check if Citizens is loaded
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens not found! Kernel Guardian will not work.");
            getLogger().severe("Please install Citizens: https://ci.citizensnpcs.co/job/Citizens2/");
        }

        // Initialize user study database
        getLogger().info("Initializing user study database...");
        SQLiteStudyDatabase.initializeDatabase();
        SQLiteStudyDatabase.testConnection();
        getLogger().info("✓ User study database ready");

        // World & room infrastructure
        configManager = new ConfigManager(this);

        worldManager = new WorldManager(this);
        worldManager.initialize();

        roomRegistry = new RoomRegistry(this);
        roomRegistry.loadFromConfig();

        structureManager = new StructureManager(this, worldManager, roomRegistry);
        structureManager.initialize();

        locationRegistry = new LocationRegistry(this);
        locationRegistry.loadFromConfig();

        // Content systems (load YAML files)
        dialogueManager = new DialogueManager(this);
        dialogueManager.load();

        questionBank = new QuestionBank(this);
        questionBank.load();

        // Session & journey tracking
        sessionManager = new SessionManager();
        journeyTracker = new JourneyTracker();
        progressTracker = new ProgressTracker();

        // Game systems
        hintSystem = new HintSystem(sessionManager, dialogueManager, journeyTracker);
        quizManager = new QuizManager(sessionManager, roomRegistry, questionBank);

        // Room display
        if (configManager.isRoomDisplayEnabled()) {
            int interval = configManager.getRoomDisplayInterval();
            boolean clear = getConfig().getBoolean("roomDisplay.clearWhenOutside", true);
            roomDisplayManager = new RoomDisplayManager(this, roomRegistry, interval, clear);
            roomDisplayManager.start();
        }

        // Teleport buttons
        boolean debugClicks = configManager.isDebugMode();
        teleportManager = new TeleportManager(this, locationRegistry, debugClicks);
        teleportManager.register();

        // Calculator (must be before ChoiceButtonHandler)
        calculatorListener = new CalculatorListener(this, journeyTracker);
        calculatorListener.register();

        // Swap clock (must be before ChoiceButtonHandler and RoomChangeListener)
        swapClockManager = new SwapClockManager(this, journeyTracker, dialogueManager);

        // Journey map (must be before ChoiceButtonHandler)
        journeyMapManager = new JourneyMapManager(this, journeyTracker);

        // Choice buttons
        choiceButtonHandler = new ChoiceButtonHandler(this, journeyTracker, dialogueManager, questionBank, progressTracker, locationRegistry, calculatorListener, swapClockManager, journeyMapManager);
        choiceButtonHandler.register();

        // NPC / Guardian
        kernelGuardian = new KernelGuardian(this);

        guardianHandler = new GuardianInteractionHandler(
            this, configManager, kernelGuardian,
            dialogueManager, hintSystem, journeyTracker
        );
        getServer().getPluginManager().registerEvents(guardianHandler, this);

        roomChangeListener = new RoomChangeListener(
            this, kernelGuardian, roomRegistry, locationRegistry,
            dialogueManager, journeyTracker, calculatorListener,
            progressTracker, choiceButtonHandler, swapClockManager
        );
        getServer().getPluginManager().registerEvents(roomChangeListener, this);

        getLogger().info("OSCity enabled!");
    }

    @Override
    public void onDisable() {
        if (kernelGuardian != null) {
            kernelGuardian.destroy();
        }
        getLogger().info("OSCity disabled!");
    }

    // Getters
    public ConfigManager getConfigManager()     { return configManager; }
    public RoomRegistry getRoomRegistry()       { return roomRegistry; }
    public LocationRegistry getLocationRegistry() { return locationRegistry; }
    public KernelGuardian getKernelGuardian()   { return kernelGuardian; }
    public DialogueManager getDialogueManager() { return dialogueManager; }
    public QuestionBank getQuestionBank()       { return questionBank; }
    public JourneyTracker getJourneyTracker()   { return journeyTracker; }
    public SessionManager getSessionManager()   { return sessionManager; }
    public QuizManager getQuizManager()         { return quizManager; }
}
