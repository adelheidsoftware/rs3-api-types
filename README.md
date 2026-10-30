# rs3-api-types

LuaCATS definitions of the RuneScape (RS3) Client Plugin API, for the Lua Language Server (LuaLS) and for tools such as
[Oneiro](https://github.com/adelheidsoftware/oneiro). The API is generated from Jagex's public documentation at
<https://developer.runescape.com/rs/>, and the game's constants, such as `id.NPC.HANS`, from the game client.

> [!WARNING]
> RuneScape's Plugin API is not yet stable. Data is subject to change frequently.

## Using the definitions in your editor

Clone this repository next to your plugin, and point LuaLS at its `generated/library/` folder in your plugin's
`.luarc.json`:

```json
{
    "runtime.version": "Lua 5.4",
    "workspace.library": ["../rs3-api-types/generated/library"]
}
```

That types `id.NPC.HANS` as an integer and `config.NPC.HANS` as an `NPCType`, but doesn't know the names. For the
names, with each ID's value, add `generated/constants/` too. There are over 800,000, so LuaLS takes about half a minute
longer to start:

```json
{
    "runtime.version": "Lua 5.4",
    "workspace.library": ["../rs3-api-types/generated/library", "../rs3-api-types/generated/constants"]
}
```

## What's here

- `generated/library/`: the API's definitions, one file per page of the docs.
- `generated/constants/`: a CSV of each namespace of the game's `id` table, `id,NAME`, with a variable's value type,
  base variable and bit mask after them, as the client's console command `pan` gives them; and a definition file of
  each namespace's names.
- `src/`: the generator. `tools/constants-dump/`: the development plugin that dumps the variables' types from the
  client.

Everything in `generated/` is written by the generator, so don't edit it by hand.

## Regenerating

The generator needs JDK 25, which Gradle downloads if you have none:

```sh
./gradlew generate            # fetches the pages the cache lacks, then rewrites generated/
./gradlew generate -Prefresh  # fetches every page again
./gradlew generate -Poffline  # reads only the pages already in cache/
./gradlew constants           # reads the names from the beta client's cache and the dump, then generates
```

It fetches the docs one page at a time, a second apart, and keeps them in `cache/`, which is never committed. At the end
it lists anything in the docs it did not understand. Three files of its own fill the gaps:

- [facts.txt](src/main/resources/org/adelheid/rs3types/facts.txt) says how to read pages whose layout doesn't say it
  all, such as a heading that names the wrong function.
- [corrections.txt](src/main/resources/org/adelheid/rs3types/corrections.txt) corrects the API where the client differs
  from the docs, or where only the docs' prose or examples say it. Each correction gives its evidence: the docs page, or
  the date it was checked in the game client. A function documented with a parameter it doesn't take is one example.
- [base.lua](src/main/resources/org/adelheid/rs3types/base.lua) declares by hand what the docs describe only in prose.

When the docs catch up with a fact or a correction, the generator reports it so it can be deleted.

The constants' names come from the game client's cache, which the launcher keeps current: `./gradlew constants`
reads them from `C:\ProgramData\Jagex\RuneScape-BETA` (`-Pcache=<folder>` for another cache) and prints how each
namespace changed. The variables' value types and bit masks come from the dump plugin, run as
[its README](tools/constants-dump/README.md) says, when they change; without a fresh dump the task keeps the ones
already written.

`./gradlew build` compiles the generator and runs its tests.

## Licence

The generator is licensed under the MIT licence. The API's names and descriptions and the game's constants in
`generated/` belong to Jagex; see [NOTICE.md](NOTICE.md).
