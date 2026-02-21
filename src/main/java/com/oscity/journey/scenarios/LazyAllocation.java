package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class LazyAllocation implements JourneyScenario {

    @Override public String getDisplayName()      { return "Lazy Allocation"; }
    @Override public int    getNumber()           { return 7; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "page_fault"; }
    @Override public String getPageFaultType()    { return "lazy_allocation"; }
    @Override public boolean involvesCow()        { return true; }
    @Override public boolean involvesSwap()       { return true; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0x45");
        vars.put("vaBin",       "0100 0101");
        vars.put("process",     "Process 7");
        vars.put("operation",   "read");
        vars.put("instruction", "read 0x45");
        vars.put("vpn",         "0100");
        vars.put("vpnHex",      "0x4");
        vars.put("offset",      "0101");
        vars.put("offsetHex",   "0x5");
        vars.put("pfn",         "0x9");   // Zero frame (phase 1)
        vars.put("pfnCow",      "0x6");   // New private frame (phase 2, after COW+swap)
        vars.put("expectedFloor", "1");
        vars.put("hex",  "0x45");
        vars.put("optA", "0100 0101");
        vars.put("optB", "0100 0100");
        vars.put("optC", "0101 0100");
    }
}
