# KotlinRangeMapExtractor

A tool for renaming symbols (classes, methods, fields, parameters, and variables) in Kotlin source code using .srg, .tsrg, etc mappings files.

For porting, Minecraft, CraftBukkit, mods, plugins, etc.

## Usage

If you want to use execute jar file directoy:
```sh
java -jar krangemap-1.5-fatJar.jar --extract [SourceDir] [LibrariesDir] [RangeMapOutput]
java -jar krangemap-1.5-fatJar.jar --apply --srcRoot [SourceDir] --srcRangeMap [RangeMap] --srgFiles [SRGFile] --excFiles [ExcFile] --outDir [Output]
```
These jar files are located under maven/com/tmvkrpxl0/krangemap

If you want to use it on your project:
## Note: You MUST run this BEFORE updating/changing anything
For this script to work, your mod must compile as-is.
This means changing your Forge version or Minecraft version first will cause this script to fail.

## How to use this
This guide assumes you are on ForgeGradle/NeoGradle 5 or newer.

### 1. Make a backup!
This script changes your mod's sourcecode.
You should make a backup in case anything goes wrong.
If not already, using Git or another version control system is highly recommended, even if private.
You have been warned.

### 2. Add this line to your `build.gradle`
This script can be used by adding the following line to your `build.gradle`:
```groovy
apply from: 'https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/remapper.gradle.kts'
```
This should be near the top of the file below ALL other plugins. Here is an example:
```groovy
plugins {
    id 'blah' version '1.0' // Plugins here
}
apply plugin: 'blah' // Other plugins here
// Insert here
apply from: 'https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/remapper.gradle.kts'
```

### 3. Run this command
After adding the above line, you are now ready to update your mod by running the provided Gradle task.
You must provide the list of URLs to the SRG files using the `UPDATE_SRGS` property.
If you have more than one URL, separate using a semicolon (`;`).
```shell
gradlew -PUPDATE_SRGS=<insert urls> updateEverything
```

Example:
```shell
gradlew -PUPDATE_SRGS=https://example.com/my_cool_srg.tsrg updateEverything
```

By default, this will only apply to the `main` sourceset. 
If you have an `api` sourceset or other sourcesets, you can add these as well using the `UPDATE_SOURCESETS` property.
This is a semicolon-separated list that can be defined, for example, as `-PUPDATE_SOURCESETS="main;api"`, and would be inserted into the above command before `updateEverything`.

To use this tool in reverse, add `-PREVERSED=true` before `updateEverything`. This will use the provided SRG URLs in the opposite direction, generally for backporting.

### 4. Proceed to update your mod
You can now delete the `apply from:` line from your `build.gradle` and continue updating/backporting your mod.
You should now update your Forge version, mappings, Minecraft version, and anything else as you need to.
Happy modding!

*If you have any issues, ask me on discord, my username is tmvkrpxl0*

## See also

RangeMap extractor/applier is based on Srg2Source by MinecraftForge: https://github.com/MinecraftForge/Srg2Source
Remapper script is based on this: https://github.com/SizableShrimp/ForgeUpdatesRemapper
More remapping tools and generated .srgs: https://github.com/agaricusb/MinecraftRemapping


Btw I copied and combined readme from Shrimp's original script and srg2source to make this one. Very cool.
