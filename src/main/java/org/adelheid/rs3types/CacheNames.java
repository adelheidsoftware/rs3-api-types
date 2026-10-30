package org.adelheid.rs3types;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the names of the game's assets from the client's cache, which names every ID the client's {@code id} table
 * exposes, so that the names need no dump from inside the client. The client keeps each index of its cache in an
 * SQLite file, {@code js5-<index>.jcache}, with a table {@code cache} of groups; index 67 holds the names, one group
 * an asset type, and the group numbers are the asset type IDs the console's {@code pan} command uses. A group is
 * wrapped as {@code ZLB}, a version byte, the uncompressed length and a zlib stream; inside, a format word, a count,
 * then either one offset per ID and the names in ID order, empty where an ID has none, or pairs of an ID and an
 * offset. A name starting with a digit is exposed to Lua with an underscore in front, since a Lua identifier cannot
 * start with one. A variable group lists the plain variables, then the bit variables with an underscore in front, one
 * per bit ID from 1, which the constants dump numbers minus their ID.
 */
final class CacheNames {

	/** The beta client's cache folder. */
	static final Path BETA_CACHE = Path.of("C:\\ProgramData\\Jagex\\RuneScape-BETA");
	/** The index of the cache that holds the names. */
	static final String NAMES_INDEX = "js5-67.jcache";

	/** The namespace of {@code id} each group of the names index holds. */
	static final Map<Integer, String> GROUPS = Map.ofEntries(Map.entry(0, "Component"), Map.entry(5, "BaseAnimSet"),
			Map.entry(9, "Category"), Map.entry(12, "Cursor"), Map.entry(14, "DBRow"), Map.entry(15, "DBTable"),
			Map.entry(16, "Enum"), Map.entry(20, "Headbar"), Map.entry(21, "Hitmark"), Map.entry(24, "Interface"),
			Map.entry(25, "Inv"), Map.entry(28, "Loc"), Map.entry(29, "MapElement"), Map.entry(32, "Material"),
			Map.entry(34, "Model"), Map.entry(35, "NPC"), Map.entry(36, "Obj"), Map.entry(37, "Param"),
			Map.entry(41, "Quest"), Map.entry(44, "Seq"), Map.entry(49, "Sprite"), Map.entry(50, "Struct"),
			Map.entry(55, "VarClan"), Map.entry(56, "VarClanSetting"), Map.entry(57, "VarClient"),
			Map.entry(60, "VarObj"), Map.entry(61, "VarPlayer"), Map.entry(64, "Sound"), Map.entry(69, "Music"),
			Map.entry(80, "VarPlayerGroup"), Map.entry(89, "Achievement"), Map.entry(90, "Font"),
			Map.entry(92, "StyleSheet"), Map.entry(96, "UIAnimCurve"), Map.entry(97, "UIAnim"));
	/**
	 * The variable namespaces whose groups list bit variables after the plain ones, which are renumbered as the dump
	 * numbers them. VarClan and VarClanSetting list theirs with IDs of their own, which the dump numbers otherwise, so
	 * their names stay the dump's.
	 */
	static final Set<String> RENUMBERED = Set.of("VarPlayer", "VarClient", "VarObj");
	static final Set<String> KEPT_FROM_DUMP = Set.of("VarClan", "VarClanSetting");

	private static final byte[] WRAPPER = {'Z', 'L', 'B'};
	private static final int DENSE = 1;
	private static final int SPARSE = 2;

	/** The rows of each namespace the cache names, {@code id,NAME}, and how many IDs of each have no name. */
	record Names(Map<String, List<String>> rows, Map<String, Integer> nameless) {

		Names {
			rows = Map.copyOf(rows);
			nameless = Map.copyOf(nameless);
		}
	}

	/** A name of a group with its ID as the group gives it. */
	record Entry(int id, String name) {
	}

	private CacheNames() {
	}

	/** Whether a cache folder has the names index. */
	static boolean has(Path cache) {
		return Files.isRegularFile(cache.resolve(NAMES_INDEX));
	}

	/**
	 * The names in a cache folder's names index, by namespace. The index is read from a copy, as the client may hold it
	 * open.
	 */
	static Names read(Path cache) throws IOException {
		Path index = cache.resolve(NAMES_INDEX);
		if (!Files.isRegularFile(index)) {
			throw new IOException("There is no names index at " + index + "; is the client installed there?");
		}
		Path copy = Files.createTempFile("rs3-api-types", ".jcache");
		try {
			Files.copy(index, copy, StandardCopyOption.REPLACE_EXISTING);
			Map<String, List<String>> rows = new LinkedHashMap<>();
			Map<String, Integer> nameless = new HashMap<>();
			try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy);
					Statement query = connection.createStatement();
					ResultSet results = query.executeQuery("SELECT KEY, DATA FROM cache ORDER BY KEY")) {
				while (results.next()) {
					String namespace = GROUPS.get(results.getInt(1));
					if (namespace == null) {
						continue;
					}
					List<Entry> entries = parseGroup(inflate(results.getBytes(2)));
					List<String> named = rows(entries, namespace);
					rows.put(namespace, named);
					if (named.size() < entries.size()) {
						nameless.put(namespace, entries.size() - named.size());
					}
				}
			} catch (SQLException e) {
				throw new IOException("Could not read the names index " + index + ": " + e.getMessage(), e);
			}
			return new Names(rows, nameless);
		} finally {
			Files.deleteIfExists(copy);
		}
	}

	/** A group's bytes unwrapped: inflated after its ZLB header, or as they are without one. */
	static byte[] inflate(byte[] data) throws IOException {
		if (data.length < 8 || data[0] != WRAPPER[0] || data[1] != WRAPPER[1] || data[2] != WRAPPER[2]) {
			return data;
		}
		int length = (data[4] & 0xFF) << 24 | (data[5] & 0xFF) << 16 | (data[6] & 0xFF) << 8 | (data[7] & 0xFF);
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(data, 8, data.length - 8);
			byte[] plain = new byte[length];
			int done = 0;
			while (done < length && !inflater.finished()) {
				int count = inflater.inflate(plain, done, length - done);
				if (count == 0 && inflater.needsInput()) {
					break;
				}
				done += count;
			}
			if (done != length) {
				throw new IOException("A group of the names index inflated to " + done + " bytes of " + length + ".");
			}
			return plain;
		} catch (DataFormatException e) {
			throw new IOException("A group of the names index is not a zlib stream: " + e.getMessage(), e);
		} finally {
			inflater.end();
		}
	}

	/**
	 * The entries of a group, every ID with a name, in the group's order: dense groups by ID, sparse ones as listed.
	 * A name is given as the group spells it.
	 */
	static List<Entry> parseGroup(byte[] plain) throws IOException {
		int format = word(plain, 0);
		int count = word(plain, 4);
		List<Entry> entries = new ArrayList<>();
		if (format == DENSE) {
			int base = 8 + 4 * count;
			for (int i = 0; i < count; i++) {
				// An ID without a name has an offset of all ones
				int offset = word(plain, 8 + 4 * i);
				entries.add(new Entry(i, offset < 0 ? "" : nameAt(plain, base + offset)));
			}
		} else if (format == SPARSE) {
			int base = 8 + 8 * count;
			for (int i = 0; i < count; i++) {
				int id = word(plain, 8 + 8 * i);
				int offset = word(plain, 12 + 8 * i);
				entries.add(new Entry(id, offset < 0 ? "" : nameAt(plain, base + offset)));
			}
		} else {
			throw new IOException("A group of the names index has the format " + format + ", which is not known.");
		}
		return entries;
	}

	/**
	 * The rows of a namespace from a group's entries, {@code id,NAME}, as the client exposes the names: without the
	 * entries that have no name, a name starting with a digit given an underscore, and a renumbered namespace's bit
	 * variables numbered minus their bit ID, without their underscore.
	 */
	static List<String> rows(List<Entry> entries, String namespace) {
		List<String> rows = new ArrayList<>();
		int firstBit = -1;
		for (Entry entry : entries) {
			String name = entry.name();
			if (name.isEmpty()) {
				continue;
			}
			int id = entry.id();
			if (RENUMBERED.contains(namespace) && name.startsWith("_")) {
				if (firstBit < 0) {
					firstBit = id;
				}
				id = -(id - firstBit + 1);
				name = name.substring(1);
			}
			if (Character.isDigit(name.charAt(0))) {
				name = "_" + name;
			}
			rows.add(id + "," + name);
		}
		return rows;
	}

	private static int word(byte[] data, int at) throws IOException {
		if (at + 4 > data.length) {
			throw new IOException("A group of the names index ends inside its table.");
		}
		return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
				| (data[at + 3] & 0xFF);
	}

	private static String nameAt(byte[] data, int at) throws IOException {
		if (at < 0 || at > data.length) {
			throw new IOException("A group of the names index points outside itself.");
		}
		int end = at;
		while (end < data.length && data[end] != 0) {
			end++;
		}
		return new String(data, at, end - at, StandardCharsets.ISO_8859_1);
	}
}
