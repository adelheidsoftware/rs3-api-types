package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The names read from a group of the cache's names index, in both of its layouts, and merged with a dump. */
class CacheNamesTest {

	@TempDir
	Path root;

	/**
	 * A dense group: a format word 1, a count, an offset per ID and the names in ID order, an offset of all ones for an
	 * ID without a name.
	 */
	private static byte[] dense(String... names) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream table = new ByteArrayOutputStream();
		int offset = 0;
		for (String name : names) {
			if (name.isEmpty()) {
				word(out, -1);
				continue;
			}
			word(out, offset);
			byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
			table.write(bytes, 0, bytes.length);
			table.write(0);
			offset += bytes.length + 1;
		}
		ByteArrayOutputStream group = new ByteArrayOutputStream();
		word(group, 1);
		word(group, names.length);
		group.writeBytes(out.toByteArray());
		group.writeBytes(table.toByteArray());
		return group.toByteArray();
	}

	/** A sparse group: a format word 2, a count, then pairs of an ID and an offset. */
	private static byte[] sparse(Map<Integer, String> names) {
		ByteArrayOutputStream pairs = new ByteArrayOutputStream();
		ByteArrayOutputStream table = new ByteArrayOutputStream();
		int offset = 0;
		for (Map.Entry<Integer, String> entry : names.entrySet()) {
			word(pairs, entry.getKey());
			word(pairs, offset);
			byte[] bytes = entry.getValue().getBytes(StandardCharsets.ISO_8859_1);
			table.write(bytes, 0, bytes.length);
			table.write(0);
			offset += bytes.length + 1;
		}
		ByteArrayOutputStream group = new ByteArrayOutputStream();
		word(group, 2);
		word(group, names.size());
		group.writeBytes(pairs.toByteArray());
		group.writeBytes(table.toByteArray());
		return group.toByteArray();
	}

	private static void word(ByteArrayOutputStream out, int value) {
		out.write(value >>> 24);
		out.write(value >>> 16);
		out.write(value >>> 8);
		out.write(value);
	}

	@Test
	void readsADenseGroupInIdOrderAndLeavesOutTheNameless() throws IOException {
		List<CacheNames.Entry> entries = CacheNames.parseGroup(dense("HANS", "", "9GUIDE_EGG"));
		assertEquals(List.of(new CacheNames.Entry(0, "HANS"), new CacheNames.Entry(1, ""),
				new CacheNames.Entry(2, "9GUIDE_EGG")), entries);
		// A name that starts with a digit gets an underscore, as the client exposes it
		assertEquals(List.of("0,HANS", "2,_9GUIDE_EGG"), CacheNames.rows(entries, "NPC"));
	}

	@Test
	void readsASparseGroupByItsIds() throws IOException {
		Map<Integer, String> names = new LinkedHashMap<>();
		names.put(16, "GROUP_TYPE");
		names.put(21, "LOOTSHARE");
		List<CacheNames.Entry> entries = CacheNames.parseGroup(sparse(names));
		assertEquals(List.of("16,GROUP_TYPE", "21,LOOTSHARE"), CacheNames.rows(entries, "VarPlayerGroup"));
	}

	@Test
	void numbersBitVariablesAsTheDumpDoes() throws IOException {
		// After the plain variables come the bit variables, with an underscore, one per bit ID from 1
		List<CacheNames.Entry> entries = CacheNames.parseGroup(dense("LASTCASTSPELL", "", "_ZAROS_SPELLBOOK",
				"_POPUP_SHOWN"));
		assertEquals(List.of("0,LASTCASTSPELL", "-1,ZAROS_SPELLBOOK", "-2,POPUP_SHOWN"),
				CacheNames.rows(entries, "VarPlayer"));
	}

	@Test
	void inflatesAWrappedGroupAndRefusesAnotherFormat() throws IOException {
		byte[] plain = dense("HANS");
		Deflater deflater = new Deflater();
		deflater.setInput(plain);
		deflater.finish();
		byte[] compressed = new byte[plain.length + 64];
		int length = deflater.deflate(compressed);
		deflater.end();
		ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
		wrapped.writeBytes(new byte[] {'Z', 'L', 'B', 1});
		word(wrapped, plain.length);
		wrapped.write(compressed, 0, length);
		byte[] inflated = CacheNames.inflate(wrapped.toByteArray());
		assertEquals(List.of("0,HANS"), CacheNames.rows(CacheNames.parseGroup(inflated), "NPC"));
		ByteArrayOutputStream other = new ByteArrayOutputStream();
		word(other, 3);
		word(other, 0);
		assertThrows(IOException.class, () -> CacheNames.parseGroup(other.toByteArray()));
	}

	@Test
	void mergesTheCacheNamesWithTheDumpsColumnsAndKeepsWhatTheCacheLacks() throws IOException {
		Path folder = root.resolve("constants");
		Files.createDirectories(folder);
		Constants.Dump dump = new Constants.Dump(List.of("NPC", "VarPlayer", "VarClan"), List.of("NPC"),
				List.of("VarPlayer", "VarClan"),
				Map.of("NPC", List.of("0,HANS"), "VarPlayer", List.of("-1,QUEST_BIT,INTEGER,12664,0xffff0000",
						"3,LASTCASTSPELL,INTEGER"), "VarClan", List.of("-7,CLAN_BIT,INTEGER,9,0x1")),
				Map.of("NPC", 1));
		CacheNames.Names names = new CacheNames.Names(Map.of("NPC", List.of("0,HANS", "1,MAN"),
				"VarPlayer", List.of("3,LASTCASTSPELL", "-1,QUEST_BIT"), "VarClan", List.of("9274,_CLAN_BIT")),
				Map.of("NPC", 2));

		Constants.Dump merged = Constants.merge(dump, names, folder);

		// The cache's names, with the dump's columns by ID; VarClan stays the dump's, whose numbering differs
		assertEquals(List.of("0,HANS", "1,MAN"), merged.rows().get("NPC"));
		// In the dump's order: by ID, the bit variables' negative IDs first
		assertEquals(List.of("-1,QUEST_BIT,INTEGER,12664,0xffff0000", "3,LASTCASTSPELL,INTEGER"),
				merged.rows().get("VarPlayer"));
		assertEquals(List.of("-7,CLAN_BIT,INTEGER,9,0x1"), merged.rows().get("VarClan"));
		assertEquals(Map.of("NPC", 2), merged.nameless());
		assertEquals(List.of("NPC", "VarPlayer", "VarClan"), merged.namespaces());
	}

	@Test
	void mergesWithoutADumpFromTheCsvsAlreadyWritten() throws IOException {
		Path folder = root.resolve("constants");
		Files.createDirectories(folder);
		Files.writeString(folder.resolve(Constants.TABLES), "id NPC VarPlayer\nconfig NPC\nvar VarPlayer\n");
		Files.writeString(folder.resolve("VarPlayer.csv"), "3,LASTCASTSPELL,INTEGER\n");
		CacheNames.Names names = new CacheNames.Names(Map.of("NPC", List.of("0,HANS"),
				"VarPlayer", List.of("3,LASTCASTSPELL", "4,NEW_VAR")), Map.of());

		Constants.Dump merged = Constants.merge(null, names, folder);

		assertEquals(List.of("NPC", "VarPlayer"), merged.namespaces());
		assertEquals(List.of("NPC"), merged.config());
		assertEquals(List.of("3,LASTCASTSPELL,INTEGER", "4,NEW_VAR"), merged.rows().get("VarPlayer"));
		assertThrows(IOException.class, () -> Constants.merge(null, null, folder));
	}
}
