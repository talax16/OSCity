package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class LazyLoading implements JourneyScenario {

    @Override public String getDisplayName()      { return "Lazy Loading"; }
    @Override public int    getNumber()           { return 6; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "page_fault"; }
    @Override public String getPageFaultType()    { return "lazy_loading"; }
    @Override public boolean involvesCow()        { return false; }
    @Override public boolean involvesSwap()       { return true; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0x8E");
        vars.put("vaBin",       "1000 1110");
        vars.put("process",     "Process 6");
        vars.put("operation",   "load");
        vars.put("instruction", "load treasure_map.bin 0x8E");
        vars.put("vpn",         "1000");
        vars.put("vpnHex",      "0x8");
        vars.put("offset",      "1110");
        vars.put("offsetHex",   "0xE");
        vars.put("pfn",         "0x5");   // Final PFN after swap + disk load
        vars.put("file",        "treasure_map.bin");
        vars.put("pageIndex",   "0");
        vars.put("diskBlock",   "C");
        vars.put("expectedFloor", "2");
        vars.put("hex",  "0x8E");
        vars.put("optA", "1000 1110");
        vars.put("optB", "1000 1101");
        vars.put("optC", "0111 1110");
        // Page-index quiz vars (calculator visit 2)
        vars.put("optA_pg", "0");
        vars.put("optB_pg", "1");
        vars.put("optC_pg", "14");
    }
}
