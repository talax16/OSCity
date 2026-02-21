package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class PermissionViolation implements JourneyScenario {

    @Override public String getDisplayName()      { return "Permission Violation"; }
    @Override public int    getNumber()           { return 3; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "segfault"; }
    @Override public String getPageFaultType()    { return null; }
    @Override public boolean involvesCow()        { return false; }
    @Override public boolean involvesSwap()       { return false; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0xFF");
        vars.put("vaBin",       "1111 1111");
        vars.put("process",     "Process 3");
        vars.put("operation",   "read");
        vars.put("instruction", "read 0xFF");
        vars.put("vpn",         "1111");
        vars.put("vpnHex",      "0xF");
        vars.put("offset",      "1111");
        vars.put("offsetHex",   "0xF");
        vars.put("pfn",         "N/A");
        vars.put("expectedFloor", "3");
        vars.put("hex",  "0xFF");
        vars.put("optA", "1111 1111");
        vars.put("optB", "1111 0000");
        vars.put("optC", "0000 1111");
    }
}
