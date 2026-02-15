# OSCity (Minecraft OS Paging Simulator)

OSCity is a Minecraft (Paper) server world + custom plugin used to visualise Operating Systems memory-management concepts (paging, page tables, page faults, etc.) as rooms, teleports, and interactive NPC-driven tasks inside a Minecraft world.

## What’s in this repo
- `OSCity/` — Java plugin source (Gradle)

## Requirements
- Java 21+ (recommended: Java 21)
- Paper server for Minecraft 1.21.x
- (Optional) Citizens + Denizen for NPCs/scripts

## How to run (local)
1. Download a Paper jar for Minecraft 1.21.x.
2. Create a server folder (e.g. `PaperServer/`) and put the Paper jar inside it.
3. Put the world folder(s) into the server folder:
   will provide it in a zip file
4. Build the plugin:
   cd OSCity
   ./gradlew build
5. Copy the plugin jar into the server plugins folder:
    cp build/libs/OSCity-*.jar /path/to/PaperServer/plugins/
6. Start the server:
    cd /path/to/PaperServer
    java -jar paper-*.jar
7. Join on Minecraft multiplayer
