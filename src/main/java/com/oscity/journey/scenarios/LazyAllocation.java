package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class LazyAllocation implements JourneyScenario {

    @Override public String getDisplayName()      { return "Lazy Allocation Journey"; }
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
        vars.put("pfn",         "N/A");   // Not allocated yet (phase 1 - before allocation)
        vars.put("pfnCow",      "0x6");   // New private frame (phase 2, after COW+swap)
        vars.put("expectedFloor", "1");
        vars.put("hex",  "0x45");
        vars.put("optA", "0100 0101");
        vars.put("optB", "0100 0100");
        vars.put("optC", "0101 0100");
        
        // Initial PTE values (before allocation - not present)
        vars.put("ptePresent",  "0");
        vars.put("pteRead",     "0");
        vars.put("pteWrite",    "0");
        vars.put("pteReadOnly", "0");
        vars.put("pteUser",     "1");
        vars.put("pteKernel",   "0");
        vars.put("pteFileBacked", "0");
        vars.put("pteAnon",     "1");
        vars.put("pteInSwap",   "0");
    }
}
