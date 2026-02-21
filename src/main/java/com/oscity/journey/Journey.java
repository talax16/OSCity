package com.oscity.journey;

import com.oscity.journey.scenarios.LazyAllocation;
import com.oscity.journey.scenarios.LazyLoading;
import com.oscity.journey.scenarios.LuckyJourney;
import com.oscity.journey.scenarios.PermissionViolation;
import com.oscity.journey.scenarios.PureCOWJourney;
import com.oscity.journey.scenarios.SwappedOutPage;
import com.oscity.journey.scenarios.TLBMissNoFault;

import java.util.Map;

/**
 * The seven learning journeys through OSCity.
 * Each constant delegates all data and behaviour to its JourneyScenario implementation.
 *
 * Public fields are preserved for backward-compatibility with existing callers
 * (journey.permissionAnswer, journey.number, etc.) — they are populated from
 * the scenario object at enum construction time.
 */
public enum Journey {

    LUCKY               (new LuckyJourney()),
    TLB_MISS_ALLOW      (new TLBMissNoFault()),
    PERMISSION_VIOLATION(new PermissionViolation()),
    SWAPPED_OUT         (new SwappedOutPage()),
    PURE_COW            (new PureCOWJourney()),
    LAZY_LOADING        (new LazyLoading()),
    LAZY_ALLOCATION     (new LazyAllocation());

    // ── Public fields (backward-compatible) ───────────────────────────────────

    public final String  displayName;
    public final int     number;
    public final boolean isTlbHit;
    public final String  permissionAnswer;
    public final String  pageFaultType;     // null if not a page-fault journey

    private final JourneyScenario scenario;

    // ── Constructor ───────────────────────────────────────────────────────────

    Journey(JourneyScenario scenario) {
        this.scenario         = scenario;
        this.displayName      = scenario.getDisplayName();
        this.number           = scenario.getNumber();
        this.isTlbHit         = scenario.isTlbHit();
        this.permissionAnswer = scenario.getPermissionAnswer();
        this.pageFaultType    = scenario.getPageFaultType();
    }

    // ── Delegated methods ─────────────────────────────────────────────────────

    public void initVars(Map<String, String> vars) {
        scenario.initVars(vars);
    }

    public boolean involvesCow() {
        return scenario.involvesCow();
    }

    public boolean involvesSwap() {
        return scenario.involvesSwap();
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static Journey fromNumber(int n) {
        for (Journey j : values()) {
            if (j.number == n) return j;
        }
        return null;
    }

    public static Journey random() {
        Journey[] vals = values();
        return vals[(int) (Math.random() * vals.length)];
    }
}
