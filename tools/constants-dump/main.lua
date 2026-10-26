-- main.lua
-- rs3-api-types' constants dump, a development plugin that is never published. It writes the names of every
-- namespace of the client's id table, with their IDs, into the plugin's persistent database, which the client keeps in
-- PluginData.db for ./gradlew constants to read. A variable also gets its value type and, for a bit variable, the
-- variable it lives in and its bit mask, the columns the console's pan command writes. One namespace runs a server tick,
-- each scheduled before the one before it runs, so that an error ends that namespace alone.

local TICK = "constantsDump.tick"
-- The rows stored in one string, so that no stored value grows large
local ROWS_PER_CHUNK = 1000

-- The subscription waiting for the next tick, removed if the plugin stops first
local waiting = nil

-- The names of the namespaces a table of the client's holds (id, config or var), sorted
local function namespaces(t)
    local names = {}
    for name, value in pairs(t) do
        if type(value) == "table" then
            names[#names + 1] = name
        end
    end
    table.sort(names)
    return names
end

-- The columns after a variable's ID and name: its value type, and for a bit variable the ID of the variable it lives
-- in and its bit mask, which ./gradlew constants writes in hexadecimal, as pan does
local function variableColumns(variable)
    if variable == nil then
        return ""
    end
    local columns = "," .. tostring(ValueType.FromID(variable.valueType))
    if variable.isVarBit then
        local base = variable.baseVar
        columns = columns .. "," .. (base ~= nil and tostring(base.id) or "") .. "," .. tostring(variable.varBitMask)
    end
    return columns
end

-- Stores a namespace's rows, sorted by ID, in strings of ROWS_PER_CHUNK lines: dump.<name>.1, dump.<name>.2 and on,
-- then how many rows and strings there are, which marks the namespace complete
local function dumpNamespace(name, variables)
    local rows = {}
    -- pairs hands a namespace out by ID, each with its name (id.NPC gives 0 and HANS), the reverse of reading it
    for constantID, constant in pairs(id[name]) do
        rows[#rows + 1] = { constantID, constant }
    end
    table.sort(rows, function(a, b) return a[1] < b[1] end)

    local chunks = 0
    local lines = {}
    for index, row in ipairs(rows) do
        local extra = variables ~= nil and variableColumns(variables[row[2]]) or ""
        lines[#lines + 1] = tostring(row[1]) .. "," .. row[2] .. extra
        if #lines == ROWS_PER_CHUNK or index == #rows then
            chunks = chunks + 1
            PersistentDB:SetString("dump." .. name .. "." .. chunks, table.concat(lines, "\n"))
            lines = {}
        end
    end
    PersistentDB:SetInt("dump." .. name .. ".chunks", chunks)
    PersistentDB:SetInt("dump." .. name .. ".rows", #rows)
    log(formatStr("{}: {} rows", name, #rows))
end

-- Dumps the namespaces one a tick, from the index-th on
local function schedule(names, index)
    local subscription = TICK .. "." .. index
    waiting = subscription
    Event.ServerLogic.Subscribe(subscription, function()
        Event.ServerLogic.Unsubscribe(subscription)
        waiting = nil
        local name = names[index]
        if name == nil then
            PersistentDB:SetString("dump.done", "yes")
            log(formatStr("done: {} namespaces; run ./gradlew constants in rs3-api-types", #names))
            return
        end
        schedule(names, index + 1)
        dumpNamespace(name, var[name])
    end)
end

-- The client calls PluginShutdown by this global name when the plugin is turned off or reloaded
function PluginShutdown()
    if waiting ~= nil then
        Event.ServerLogic.Unsubscribe(waiting)
        waiting = nil
    end
end

-- A dump replaces the last one whole, so that no namespace of an older client survives in it
PersistentDB:Clear()
local idNames = namespaces(id)
PersistentDB:SetString("dump.id", table.concat(idNames, ","))
PersistentDB:SetString("dump.config", table.concat(namespaces(config), ","))
PersistentDB:SetString("dump.var", table.concat(namespaces(var), ","))
log(formatStr("dumping {} namespaces, one a tick", #idNames))
schedule(idNames, 1)
