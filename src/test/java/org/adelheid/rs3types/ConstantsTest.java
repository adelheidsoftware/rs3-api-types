package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The constants dump read from the plugin database, the CSVs written from it, and their LuaCATS. */
class ConstantsTest {

	@TempDir
	Path root;

	/** A dump of two namespaces as the plugin stores it: NPC, whose configs config has, and VarPlayer, a var domain. */
	private static Map<String, Object> dump() {
		Map<String, Object> entries = new HashMap<>();
		entries.put("dump.done", "yes");
		entries.put("dump.id", "NPC,VarPlayer");
		entries.put("dump.config", "NPC");
		entries.put("dump.var", "VarPlayer");
		entries.put("dump.NPC.1", "0,HANS\n1,MAN");
		entries.put("dump.NPC.2", "7,\n9,FARMER1");
		entries.put("dump.NPC.chunks", 2);
		entries.put("dump.NPC.rows", 4);
		entries.put("dump.VarPlayer.1", "-5,QUEST_BIT,INTEGER,12664,4294901760\n3,LASTCASTSPELL,INTEGER");
		entries.put("dump.VarPlayer.chunks", 1);
		entries.put("dump.VarPlayer.rows", 2);
		return entries;
	}

	@Test
	void readsTheDumpWithItsMasksInHexadecimalAndLeavesOutANamelessId() throws IOException {
		Constants.Dump dump = Constants.read(dump());
		assertEquals(List.of("NPC", "VarPlayer"), dump.namespaces());
		assertEquals(List.of("NPC"), dump.config());
		assertEquals(List.of("VarPlayer"), dump.var());
		assertEquals(List.of("0,HANS", "1,MAN", "9,FARMER1"), dump.rows().get("NPC"));
		assertEquals(Map.of("NPC", 1), dump.nameless());
		assertEquals(List.of("-5,QUEST_BIT,INTEGER,12664,0xffff0000", "3,LASTCASTSPELL,INTEGER"),
				dump.rows().get("VarPlayer"));
	}

	@Test
	void refusesADumpThatIsNotComplete() {
		Map<String, Object> unfinished = dump();
		unfinished.remove("dump.done");
		assertThrows(IOException.class, () -> Constants.read(unfinished));
		Map<String, Object> failedNamespace = dump();
		failedNamespace.remove("dump.VarPlayer.rows");
		assertThrows(IOException.class, () -> Constants.read(failedNamespace));
		Map<String, Object> shortNamespace = dump();
		shortNamespace.put("dump.NPC.rows", 5);
		assertThrows(IOException.class, () -> Constants.read(shortNamespace));
	}

	@Test
	void writesTheCsvsAndSaysHowEachNamespaceChanged() throws IOException {
		Path folder = root.resolve("constants");
		Files.createDirectories(folder);
		Files.writeString(folder.resolve("Gone.csv"), "0,OLD\n");
		// MAN is new, WOMAN gone, and FARMER1's ID changed
		Files.writeString(folder.resolve("NPC.csv"), "0,HANS\n1,WOMAN\n8,FARMER1\n");

		List<String> report = Constants.write(Constants.read(dump()), folder);

		assertEquals(List.of("NPC: 3 rows, 1 added, 1 removed, 1 changed; 1 ID without a name left out",
				"VarPlayer: 2 rows, new", "Gone.csv: deleted, as the client no longer has its namespace"), report);
		assertEquals("0,HANS\n1,MAN\n9,FARMER1\n", Files.readString(folder.resolve("NPC.csv"), StandardCharsets.UTF_8));
		assertFalse(Files.exists(folder.resolve("Gone.csv")));
		assertEquals(Map.of("id", List.of("NPC", "VarPlayer"), "config", List.of("NPC"), "var", List.of("VarPlayer")),
				Constants.tables(folder));
	}

	@Test
	void readsTheDumpsEntriesFromThePluginDatabase() throws IOException, SQLException {
		Path database = root.resolve("PluginData.db");
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
			try (Statement create = connection.createStatement()) {
				create.execute("CREATE TABLE DataStore (PluginID TEXT, Key TEXT, Type INTEGER, Value BLOB)");
			}
			try (PreparedStatement insert =
					connection.prepareStatement("INSERT INTO DataStore VALUES (?, ?, ?, ?)")) {
				// An Int is four little-endian bytes, a String UTF-8 ending in a NUL; another plugin's entry is
				// left out
				Object[][] rows = {
						{Constants.PLUGIN, "dump.NPC.rows", 1, new byte[] {(byte) 0x98, (byte) 0x80, 0, 0}},
						{Constants.PLUGIN, "dump.done", 3, "yes\0".getBytes(StandardCharsets.UTF_8)},
						{"API Probe", "apiProbe.run", 1, new byte[] {12, 0, 0, 0}},
				};
				for (Object[] row : rows) {
					for (int column = 0; column < row.length; column++) {
						insert.setObject(column + 1, row[column]);
					}
					insert.executeUpdate();
				}
			}
		}
		assertEquals(Map.of("dump.NPC.rows", 32920, "dump.done", "yes"), Constants.entries(database));
	}

	@Test
	void writesTheTablesAndEachNamespacesNames() {
		String tables = ConstantsWriter.tables(List.of("NPC"), List.of("NPC"), List.of(), Map.of("NPC", "NPCType"));
		String expectedTables = """
				---@class id
				---@field NPC id.NPC
				id = {}

				---The IDs of the NPC namespace, by name.
				---@class id.NPC
				---@field [string] integer
				---@field FromID fun(id: integer): string The name of an ID.
				""";
		assertEquals(true, tables.contains(expectedTables), tables);
		assertEquals(true, tables.contains("""
				---The NPC configs, by name.
				---@class config.NPC
				---@field [string] NPCType
				---@field FromID fun(id: integer): NPCType The config of an ID.
				"""), tables);

		assertEquals("""
				---@meta
				-- Generated by rs3-api-types from the game client's names, in generated/constants/VarPlayer.csv; \
				regenerate it
				-- with ./gradlew generate rather than editing it.

				---@class id.VarPlayer
				---@field QUEST_BIT -5
				---@field ["end"] 3

				---@class var.VarPlayer
				---@field QUEST_BIT VarType Its values are INTEGER, in the bits 0xffff0000 of VarPlayer 12664.
				---@field ["end"] VarType Its values are INTEGER.
				""", ConstantsWriter.names("VarPlayer",
				List.of("-5,QUEST_BIT,INTEGER,12664,0xffff0000", "3,end,INTEGER"), null, true));
	}
}
