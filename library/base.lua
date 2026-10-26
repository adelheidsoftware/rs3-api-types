---@meta
-- What the docs' pages leave out, written by hand for rs3-api-types: the tables the API's namespaces live in, and the
-- parts of the API the docs describe in prose and examples rather than in signatures and tables.

-- The client's interface toolkit, which holds the components, interfaces and their styles
ui = {}

-- The game's tables, among them its options, game.options
game = {}

---An integer the docs type as 64 bits wide, Long or Uint64, which a Lua integer holds.
---@alias Long integer

---The functions subscribed to an event, each under a reference name. When the client sends the event, they run in the
---order they were subscribed. Subscribing under a name the plugin has used for the event already replaces that
---function; other events, and other plugins, keep their own names.
---@class EventSubscription<T, R>
---@field Subscribe fun(referenceName: string, callback: fun(payload: T): R) Subscribes a function to the event under a reference name.
---@field Unsubscribe fun(referenceName: string) Unsubscribes the function subscribed to the event under a reference name.

---@class Plugin
---@field urls table<string, PluginURL> The base URLs the plugin's manifest names, by their aliases, as in `Plugin.urls.wiki`.
---@field resources table The plugin's resources, by their file names without extensions; a folder is a table of its own, as in `Plugin.resources.images.menu.background`.

---A base URL the plugin's manifest names, which `Plugin.urls` holds by its alias, and an `HTTPRequest` starts from.
---@class PluginURL
local PluginURL = {}

---An image resource of the plugin's, from a Portable Network Graphics (.png) file in its resources folder.
---@class PluginImageResource : PluginResource
local PluginImageResource = {}

---An audio resource of the plugin's, from an Ogg Vorbis (.ogg) file in its resources folder.
---@class PluginAudioResource : PluginResource
local PluginAudioResource = {}
