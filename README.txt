FRONTIER SURVIVAL
Version 0.2.0 | Minecraft Java 1.20.1 | Forge 47.4.0 | Java 17

INSTALL
Put frontier-survival-1.20.1-forge-0.2.0.jar in the mods folder of your
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
without a backup. Replace the old JAR; never install two versions at once.

NEW IN 0.2.0
Three sizes of every frontier site, each with its own buildings:
  Hamlets   small (31 wide): lookout tower, one-bed hut and cottage, stall,
              farm, 2 guards, quartermaster, 2 villagers.
            medium (39): the original fortified hamlet, 4 cottages, 2 towers.
            large (49): 4 towers of mixed heights, guard barracks, longhouses,
              cottages, smithy, storehouse, well, animal pen, farms, a market
              with two stalls, 5 guards, 2 quartermasters, 6 villagers.
  Camps     small (17): two tents round a fire, 2 bandits.
            medium (23): the original walled camp.
            large (31): walled camp with four tents incl. the leader's tent,
              a lookout, stores, 5 bandits and a leader.
  Outposts  small (15): a lone lookout with an archer and a sentry.
            medium (19): the original fenced watchtower.
            large (29): walled fort with two towers, barracks, well, stores,
              4 guards, quartermaster and a fletcher.
Every building is still its own terrain-following piece.

Random facing: each site is turned a random quarter turn (or whichever turn
needs the least earthworks on uneven ground). Buildings, doors, paths, gates
and residents all turn together.

Regional materials: sites take their look from the biome they stand in.
  Plains/forest oak and spruce   Taiga spruce and dark oak
  Birch forest birch             Dark forest dark oak and mossy stone
  Savanna acacia                 Snowy biomes spruce, dark roofs, stone
Cloth, beds and banners change colour by region, palisades use local logs,
and villagers wear their region's clothing.

No trees inside settlements: open ground inside a site becomes Settlement
Turf. It looks and tints exactly like grass, but trees, flowers, grass and
bushes cannot generate on it. Hoes still till it and shovels still make
paths; it drops dirt (silk touch keeps it). Plants already scattered onto the
site by neighbouring terrain are cleared. Farms keep their crops.

Villages: vanilla villages are about 1.5x more common (spacing 28,
separation 7, same five village types). New chunks around a village gain:
  - a palisade ring following the terrain around all its buildings, in the
    village's regional style (desert villages use cut sandstone),
  - gates wherever its roads leave, with tall lantern-lit gate posts,
  - lantern posts along the wall so it stays lit at night,
  - roofed watchtowers with a ladder, deck, parapet and an archer guard,
  - melee guards holding each gate,
  - a market stall near the centre with a Village charter (reputation works
    there like in hamlets), a quartermaster and supply chests.
Palisades skip water and never cut through houses, fields or other village
pieces. These additions are placed per chunk as the village generates, so
villages in already explored land are not changed.

Fixes: crops in frontier farms now survive world generation; paths are
supported where caves or ravines hollow the ground beneath them.

EARLIER FIXES
0.1.2 made sites common and fast to place: one shared placement grid (sites
never overlap each other and keep clear of villages), nine candidate
positions per region, up to five blocks of slope per building footprint with
supports, excavation and ground blending, and terrain sampled exactly the way
vanilla generates it.
0.1.1 replaced floating slabs with buildings placed at their own local
ground heights, graded paths with one-block steps and terrain-following
palisades. Heights are saved before chunks are placed, so loading order
cannot change them.

THE CORE FEATURES
1. Guards: original blue-uniform melee and bow defenders. 30 health.
   Patrol close to settlements or hold their posts (village gates and
   towers), defend against bandits/other monsters, avoid attacking creepers,
   and retaliate if attacked. Guard arrows pass through friendly residents
   and unintended players. Held melee shields are visual equipment.
2. Bandits: armed melee and bow enemies, 24 health, targeting players,
   villagers, quartermasters and guards. Small natural groups spawn on land;
   they do not burn in sunlight. Camps hold bandits and, in medium and large
   camps, a 48-health axe-wielding leader with better loot. No block
   destruction.
3. Fortified hamlets: walled frontier settlements in plains, sunflower
   plains, meadows, savanna, forests, flower and birch forests, taiga and
   snowy plains, with homes, beds, vanilla villagers with professions and
   trades, farms, bell, supplies, watchtowers, guards and a quartermaster.
   These are additional settlements, not replacements for vanilla villages.
4. Watchtower outposts: wilderness lookouts and forts with guards, a
   quartermaster or sentries, shelter and supply chests.
5. Reputation: independent saved local standing for each player at each
   hamlet, outpost and defended village, from -100 to +100, with discounts
   and consequences.

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

In a cheats-enabled test world, for example:
/locate structure frontiersurvival:terrain_hamlet_small
/locate structure frontiersurvival:terrain_fortified_hamlet
/locate structure frontiersurvival:terrain_hamlet_large
/locate structure frontiersurvival:terrain_bandit_camp_small
/locate structure frontiersurvival:terrain_bandit_camp
/locate structure frontiersurvival:terrain_bandit_camp_large
/locate structure frontiersurvival:terrain_watchtower_small
/locate structure frontiersurvival:terrain_watchtower
/locate structure frontiersurvival:terrain_watchtower_large
/locate structure #minecraft:village
Use the returned coordinates to travel/teleport. Structures have biome
restrictions and share one placement grid, so a nearby region may hold a
different frontier site; locate always reports a generated one.
Creative spawn eggs are in the Spawn Eggs tab; Settlement Turf is in
Natural Blocks.

Measured over two default-generated 3 km x 3 km worlds: 46 hamlets,
61 bandit camps and 66 outposts of all sizes, no overlapping sites, every
facing used and six regional styles. Exact counts vary by seed and biome.

LIMITS
Not the full long-term concept yet: no caravans, quests, diseases,
fatigue, new villager professions, siege events, mounted guards,
reputation housing or timed gates.
Sites allow at most five blocks of relief per building footprint and
sixteen across the settlement; path earthworks are bounded to three blocks.
Terrain shaped by other structures' terrain adaptation is not anticipated,
so a site beside one may sit slightly off the ground.
A large tree rooted just outside a site can still lean over its edge.
Village palisades leave gaps where water, tree trunks or village blocks
stand on the line.
Hands-on balance and client/multiplayer playtesting are still needed.
Guards, merchants and camp inhabitants are not automatically resurrected.
Peaceful removes hostile bandits, including camp inhabitants.
There is no forced chunk loading, terrain rebuilding or old-mod migration.

CONFIGURATION
config\frontiersurvival-common.toml controls reputation, defense reward
caps and outlaw pursuit. Restart after editing. Structure biome tags,
the shared frontier_sites placement grid, the vanilla village spacing and
bandit spawn weight can be changed with ordinary datapacks.

BUILD FROM SOURCE
Install a Java 17 JDK and set JAVA_HOME, then run:
  powershell -ExecutionPolicy Bypass -File .\Build.ps1
The script regenerates original assets, builds the mod, runs Forge
GameTests and packages dist. Gradle downloads Forge/Minecraft dependencies.
On Windows, transient output is stored under
%LOCALAPPDATA%\FrontierSurvival\build to avoid OneDrive file locks.

Original assets can also be regenerated with:
  java tools\GenerateAssets.java
The generator uses only Java 17 standard libraries.

LICENSE
New source and original generated assets: MIT (see LICENSE).
Minecraft/Forge remain their respective owners' software; the mod refers
to vanilla assets at runtime and does not redistribute the game.