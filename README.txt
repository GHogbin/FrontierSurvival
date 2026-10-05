FRONTIER SURVIVAL - INDEPENDENT MVP
Version 0.1.1 | Minecraft Java 1.20.1 | Forge 47.4.0 | Java 17

INSTALL
Put frontier-survival-1.20.1-forge-0.1.1.jar in the mods folder of your
Minecraft Java 1.20.1 / Forge 47.4.0 instance and restart the game.
Install the same JAR on both clients and server for multiplayer.
No other mod libraries are required. This is NOT Fabric, NeoForge or Bedrock.
Source ZIPs and sources JARs are development files, not installable mods.

This is a new mod: new mod ID, code, textures, structures and build tooling.
It does not require or modify any other Frontier mod. For your first play,
use a separate instance with this mod alone to avoid unrelated mod conflicts.

Back up existing saves. Start a new normal Overworld world, or explore new
chunks in a 1.20.1 save: already generated villages/chunks are not rebuilt.
Never open a world from a newer Minecraft version in 1.20.1.
Do not remove the mod from saves containing its entities/charter blocks
without a backup.

TERRAIN FIX IN 0.1.1
New hamlets and camps place cottages, towers, markets, farms and tents at
independent local ground heights instead of on a single floating slab.
Rooms, roofs, beds and ladders remain rigid and level. Only small building
footprints receive foundations; the surrounding ground is not flattened.
Paths follow a saved, graded ground profile with at most one-block steps
between neighboring path cells and level door approaches. Hamlet/camp
palisades step with the surrounding terrain. The compact watchtower outpost
uses one locally grounded footprint with shallow supports.

Unsuitable water-covered sites, cliffs, steep building footprints and
impossible approaches are skipped rather than generated in mid-air.
All sampled elevations and supports are saved before individual chunks
are placed, so loading/generation order cannot change their heights.

Replace the old JAR; never install both versions. Existing structures are
NOT rebuilt or moved. Old templates and structure IDs remain registered
for partially generated 0.1.0 structures. Only newly generated starts use
the new terrain structure IDs below. Explore NEW chunks or use a new world.

THE FIVE MVP FEATURES
1. Guards: original blue-uniform melee and bow defenders. 30 health.
   Patrol close to settlements, defend against bandits/other monsters,
   avoid attacking creepers, and retaliate if attacked. Guard arrows pass
   through friendly residents and unintended players. Held melee shields
   are visual equipment; there is no active shield-blocking AI in this MVP.
2. Bandits: armed melee and bow enemies, 24 health, targeting players,
   villagers, quartermasters and guards. Small natural groups spawn on land;
   they do not burn in sunlight. Camps have three bandits and a 48-health
   axe-wielding leader with better loot. No block destruction.
3. Fortified hamlets: original 39x39 palisade villages in plains, sunflower
   plains, meadows and savanna, with four cottages, eight beds, four ordinary
   villagers with vanilla professions/trades, farms, bell, supplies, two
   climbable watchtowers, three guards and a settlement quartermaster.
   These are additional settlements, not replacements for vanilla villages.
4. Watchtower outposts: original 13x13 wilderness towers with two guards
   (one archer), a villager, quartermaster, shelter/bed and supply chests.
5. Reputation: independent saved local standing for each player at each
   hamlet/outpost, from -100 to +100, with discounts and consequences.

PLAY
Find a settlement; right-click a guard or its gold-topped stone charter to
see your standing. Right-click the green-clad quartermaster to open normal
merchant trading: food, weapons, arrows, shields and iron armor.

Sneak-right-click a quartermaster while holding 16 wheat, 8 bread or
3 iron ingots. The exact amount is consumed for +5 local reputation and
1 emerald. One donation per player per settlement per world game-day.
Defeat bandits within 64 horizontal blocks of a charter: +2 reputation;
leaders give +5. Other monsters reward defense only when attacking a
resident or you nearby. Defense rewards are capped at +10 per settlement
per player per game-day to limit mob farming.

Harming a resident costs 3 reputation, limited to one hit penalty per
40 ticks; killing one costs another 25. Reputation survives saving,
death and reconnecting. A world game-day is 24000 advancing world ticks;
sleeping does not bypass these donation/defense limits.

Standing and quartermaster purchase prices:
  0..19      Neutral: normal prices.
  20..59     Trusted: roughly 25% cheaper.
  60..100    Champion: roughly 35% cheaper.
  -1..-14    Suspicious: roughly 25% higher prices.
  -15..-29   Unwelcome: quartermasters refuse trading.
  -30..-100  Outlaw: quartermasters refuse; guards pursue survival players.
Emerald prices are whole numbers, never less than one. Vanilla villagers
still use their normal gossip/trade system; the local discount applies
to this mod's quartermasters only.
You can donate even when unwelcome, or defend a settlement to make amends.
Creative players cannot farm donation or defense reputation.

COMMANDS
/frontier help
/frontier reputation
Both are available without cheats.

In a cheats-enabled test world:
/locate structure frontiersurvival:terrain_fortified_hamlet
/locate structure frontiersurvival:terrain_watchtower
/locate structure frontiersurvival:terrain_bandit_camp
Use the returned coordinates to travel/teleport. Structures have biome
restrictions and placement grids, so they may be a considerable walk away.
Creative spawn eggs are in the Spawn Eggs tab.

LIMITS
First MVP, not the full long-term concept. No caravans, quests, diseases,
fatigue, new professions, siege events, mounted guards, reputation housing
or timed gates yet. One original layout per structure type, fixed orientation.
Sites require at most three blocks of relief per building footprint and
twelve across the settlement; path earthworks are bounded to three blocks.
Hands-on balance and client/multiplayer playtesting are still
needed. Guards, merchants and camp inhabitants are not automatically
resurrected. Peaceful removes hostile bandits, including camp inhabitants.
There is no forced chunk loading, terrain rebuilding or old-mod migration.

CONFIGURATION
config\frontiersurvival-common.toml controls reputation, defense reward
caps and outlaw pursuit. Restart after editing. Structure biome tags,
spacing and bandit spawn weight can be changed with ordinary datapacks.

BUILD FROM SOURCE
Install a Java 17 JDK and set JAVA_HOME, then run:
  powershell -ExecutionPolicy Bypass -File .\Build.ps1
The script regenerates original assets, builds the mod, runs Forge
GameTests and packages dist. Gradle downloads Forge/Minecraft dependencies.
On Windows, transient output is stored under
%LOCALAPPDATA%\FrontierSurvival\build to avoid OneDrive file locks.
Only your new FrontierSurvival project is built.

Original assets can also be regenerated with:
  java tools\GenerateAssets.java
The generator uses only Java 17 standard libraries.

LICENSE
New source and original generated assets: MIT (see LICENSE).
Minecraft/Forge remain their respective owners' software; the mod refers
to vanilla assets at runtime and does not redistribute the game.
