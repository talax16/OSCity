package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class PureCOWJourney implements JourneyScenario {

    @Override public String getDisplayName()      { return "Pure COW"; }
    @Override public int    getNumber()           { return 5; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "protection_fault"; }
    @Override public String getPageFaultType()    { return null; }
    @Override public boolean involvesCow()        { return true; }
    @Override public boolean involvesSwap()       { return false; }

    @Override
    public void initVars(Map<String, String> vars) {
        // VA=0x64: VPN=0110 (0x6), OFFSET=0100 (0x4), DIR=01, TABLE=10 → Floor 1
        vars.put("va",          "0x64");
        vars.put("vaBin",       "0110 0100");
        vars.put("process",     "Process 5");
        vars.put("operation",   "write");
        vars.put("instruction", "write 0x64 hello");
        vars.put("vpn",         "0110");
        vars.put("vpnHex",      "0x6");
        vars.put("offset",      "0100");
        vars.put("offsetHex",   "0x4");
        vars.put("pfn",         "0x9");   // Zero frame (initial, shared)
        vars.put("pfnCow",      "0x2");   // New private frame after COW
        vars.put("expectedFloor", "1");   // DIR=01 → Floor 1
        vars.put("hex",  "0x64");
        vars.put("optA", "0110 0100");
        vars.put("optB", "0110 0010");
        vars.put("optC", "0100 0110");
    }
}
