# Constants dump

A development plugin, never published, that writes the game's constants into its persistent database for
`./gradlew constants` to read. It walks every namespace of the client's `id` table (`id.NPC`, `id.Obj`,
`id.Component`, ...), one per server tick, and stores each namespace's IDs and names. A variable (`id.VarPlayer`,
`id.VarClient`, ...) also gets its value type, and a bit variable the variable it lives in and its bit mask: the
columns the console's `pan` command copies. The client keeps the database in
`C:\ProgramData\Jagex\RuneScape-BETA\plugins\PluginData.db`.

## Running it, after a game update

1. Link the folder into the beta client's development plugins, once (Command Prompt):

   ```bat
   mklink /J "C:\ProgramData\Jagex\RuneScape-BETA\dev_plugins\constants-dump" "D:\GitHub\adelheid-software\runescape-plugins\rs3-api-types\tools\constants-dump"
   ```

2. In the client's console: `prf`, then `pon constants-dump` the first time, or `prl constants-dump` after that.
   It logs a line for each namespace, then `done`.
3. In the repository: `./gradlew constants`, which reads the database, rewrites `generated/constants/` and then
   generates the LuaCATS.
