# rs3-api-types

LuaCATS definitions of the RuneScape (RS3) Client Plugin API, for the Lua Language Server (LuaLS) and for tools such as
[Oneiro](https://github.com/adelheidsoftware/oneiro). They are generated from Jagex's public documentation at
<https://developer.runescape.com/rs/>.

> [!WARNING]
> In development. The generator reads every page of the docs but does not understand all of them yet, so `library/` is
> incomplete, and the game's constants, such as `id.NPC.HANS`, are still to come. Don't depend on it yet.

## Using the definitions in your editor

Clone this repository next to your plugin, and point LuaLS at its `library/` folder in your plugin's `.luarc.json`:

```json
{
    "runtime.version": "Lua 5.4",
    "workspace.library": ["../rs3-api-types/library"]
}
```

## Regenerating

`library/` is generated, so don't edit it by hand. The generator needs JDK 25, which Gradle downloads if you have none:

```sh
./gradlew generate            # fetches the pages the cache lacks, then rewrites library/
./gradlew generate -Prefresh  # fetches every page again
./gradlew generate -Poffline  # reads only the pages already in cache/
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

`./gradlew build` compiles the generator and runs its tests.

## Licence

The generator is licensed under the MIT licence. The API's names and descriptions in `library/` belong to Jagex; see
[NOTICE.md](NOTICE.md).
