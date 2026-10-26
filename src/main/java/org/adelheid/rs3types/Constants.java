package org.adelheid.rs3types;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the constants dump, which {@code tools/constants-dump} stores in the beta client's plugin database, and writes
 * {@code generated/constants/}: a CSV of each namespace of the client's {@code id} table, a row {@code id,NAME} for
 * each name, which for a variable goes on with its value type and, for a bit variable, the ID of the variable it lives
 * in and its bit mask, as the console's {@code pan} command copies them; and {@code tables.txt}, the namespaces of
 * {@code id}, {@code config} and {@code var}. It prints how each namespace changed since the last dump. Run from the
 * repository's root, as {@code ./gradlew constants} does, with the database's path, or the beta client's by default.
 */
public final class Constants {

	/** The beta client's plugin database, where PersistentDB keeps every plugin's entries. */
	static final Path BETA_DATABASE = Path.of("C:\\ProgramData\\Jagex\\RuneScape-BETA\\plugins\\PluginData.db");
	/** The dump's plugin, by its manifest's name, which the database keys its entries by. */
	static final String PLUGIN = "rs3-api-types Constants Dump";
	/** The file of constants/ that names the namespaces of id, config and var. */
	static final String TABLES = "tables.txt";

	// PersistentDBType's values, which the database stores each entry's type as
	private static final int INT = 1;
	private static final int STRING = 3;
	private static final Pattern COMMA = Pattern.compile(",");
	private static final Pattern SPACE = Pattern.compile(" ");
	private static final Pattern NEWLINE = Pattern.compile("\n");
	/**
	 * A row as the dump stores it: an ID, a name, and for a variable its type, its base variable and its mask. The name
	 * may be empty, as one category's is.
	 */
	private static final Pattern ROW = Pattern.compile("(-?\\d+),(\\w*)(?:,(\\w+)(?:,(\\d*),(-?\\d+))?)?");

	/**
	 * What the dump holds: the namespaces of id, config and var, and the rows of each id namespace, as constants/
	 * writes them.
	 *
	 * @param nameless how many IDs of a namespace have no name, which no row can give
	 */
	record Dump(List<String> namespaces, List<String> config, List<String> var, Map<String, List<String>> rows,
			Map<String, Integer> nameless) {

		Dump {
			namespaces = List.copyOf(namespaces);
			config = List.copyOf(config);
			var = List.copyOf(var);
			rows = Map.copyOf(rows);
			nameless = Map.copyOf(nameless);
		}
	}

	private Constants() {
	}

	public static void main(String[] args) throws IOException {
		Path database = args.length > 0 ? Path.of(args[0]) : BETA_DATABASE;
		Path constants = Path.of("").toAbsolutePath().resolve("generated/constants");
		Dump dump = read(entries(database));
		PrintStream out = System.out;
		write(dump, constants).forEach(out::println);
	}

	/**
	 * The dump's entries in a database, by key, each a String or an Integer. The database is read from a copy, as the
	 * client may hold it open.
	 */
	static Map<String, Object> entries(Path database) throws IOException {
		if (!Files.isRegularFile(database)) {
			throw new IOException("There is no plugin database at " + database + "; run the constants dump first.");
		}
		Path copy = Files.createTempFile("rs3-api-types", ".db");
		try {
			Files.copy(database, copy, StandardCopyOption.REPLACE_EXISTING);
			Map<String, Object> entries = new HashMap<>();
			try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy);
					PreparedStatement query = connection.prepareStatement(
							"SELECT Key, Type, Value FROM DataStore WHERE PluginID = ?")) {
				query.setString(1, PLUGIN);
				try (ResultSet results = query.executeQuery()) {
					while (results.next()) {
						Object value = decode(results.getInt(2), results.getBytes(3));
						if (value != null) {
							entries.put(results.getString(1), value);
						}
					}
				}
			} catch (SQLException e) {
				throw new IOException("Could not read the plugin database " + database + ": " + e.getMessage(), e);
			}
			return entries;
		} finally {
			Files.deleteIfExists(copy);
		}
	}

	/** A stored value: an Int is four little-endian bytes, a String UTF-8 ending in a NUL; null for another type. */
	private static @Nullable Object decode(int type, byte[] value) {
		if (type == INT && value.length >= 4) {
			return (value[0] & 0xFF) | (value[1] & 0xFF) << 8 | (value[2] & 0xFF) << 16 | (value[3] & 0xFF) << 24;
		}
		if (type == STRING) {
			int length = value.length > 0 && value[value.length - 1] == 0 ? value.length - 1 : value.length;
			return new String(value, 0, length, StandardCharsets.UTF_8);
		}
		return null;
	}

	/**
	 * The dump in its entries: dump.id, dump.config and dump.var name the namespaces, and each namespace's rows are in
	 * dump.NAME.1 to dump.NAME.chunks, dump.NAME.rows of them. A dump that is not complete is refused, as writing it
	 * would lose the namespaces it lacks.
	 */
	static Dump read(Map<String, Object> entries) throws IOException {
		if (!"yes".equals(entries.get("dump.done"))) {
			throw new IOException("The plugin database holds no complete constants dump; run it, and wait for its"
					+ " \"done\".");
		}
		List<String> namespaces = names(entries, "dump.id");
		Map<String, List<String>> rows = new LinkedHashMap<>();
		Map<String, Integer> nameless = new HashMap<>();
		for (String namespace : namespaces) {
			Object chunks = entries.get("dump." + namespace + ".chunks");
			Object count = entries.get("dump." + namespace + ".rows");
			if (!(chunks instanceof Integer chunkCount) || !(count instanceof Integer rowCount)) {
				throw new IOException("The dump of " + namespace + " is not complete; it ended in an error, which the"
						+ " client's console shows.");
			}
			List<String> lines = new ArrayList<>();
			for (int chunk = 1; chunk <= chunkCount; chunk++) {
				Object text = entries.get("dump." + namespace + "." + chunk);
				if (!(text instanceof String string)) {
					throw new IOException("The dump of " + namespace + " lacks its part " + chunk + ".");
				}
				NEWLINE.splitAsStream(string).map(line -> row(namespace, line)).forEach(lines::add);
			}
			if (lines.size() != rowCount) {
				throw new IOException("The dump of " + namespace + " has " + lines.size() + " rows of its "
						+ rowCount + ".");
			}
			List<String> named = lines.stream().filter(line -> !isNameless(line)).toList();
			if (named.size() < lines.size()) {
				nameless.put(namespace, lines.size() - named.size());
			}
			rows.put(namespace, named);
		}
		return new Dump(namespaces, names(entries, "dump.config"), names(entries, "dump.var"), rows, nameless);
	}

	private static List<String> names(Map<String, Object> entries, String key) throws IOException {
		if (!(entries.get(key) instanceof String names)) {
			throw new IOException("The dump lacks " + key + ".");
		}
		return names.isEmpty() ? List.of() : COMMA.splitAsStream(names).toList();
	}

	/** A row as constants/ writes it: as the dump stores it, but for a bit mask in hexadecimal, as pan writes it. */
	private static String row(String namespace, String line) {
		Matcher row = ROW.matcher(line);
		if (!row.matches()) {
			throw new IllegalArgumentException("The dump of " + namespace + " has a row of another form: " + line);
		}
		String mask = row.group(5);
		if (mask == null) {
			return line;
		}
		return row.group(1) + "," + row.group(2) + "," + row.group(3) + "," + row.group(4) + ",0x"
				+ Long.toHexString(Long.parseLong(mask) & 0xFFFF_FFFFL);
	}

	/** Whether a row has no name: nothing between its ID and the next comma or its end. */
	private static boolean isNameless(String row) {
		int comma = row.indexOf(',');
		return comma == row.length() - 1 || row.charAt(comma + 1) == ',';
	}

	/**
	 * Writes the dump into a folder, a CSV a namespace and tables.txt, deleting the CSV of a namespace the dump no
	 * longer has, and returns a line for each namespace: its rows, and how they changed since the CSV it replaces.
	 */
	static List<String> write(Dump dump, Path folder) throws IOException {
		Files.createDirectories(folder);
		List<String> report = new ArrayList<>();
		Set<String> written = new TreeSet<>();
		for (String namespace : dump.namespaces()) {
			Path csv = folder.resolve(namespace + ".csv");
			List<String> rows = dump.rows().getOrDefault(namespace, List.of());
			String change = Files.exists(csv) ? change(Files.readAllLines(csv, StandardCharsets.UTF_8), rows) : "new";
			Files.writeString(csv, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
			written.add(csv.getFileName().toString());
			int nameless = dump.nameless().getOrDefault(namespace, 0);
			report.add(namespace + ": " + rows.size() + " rows, " + change
					+ (nameless == 0 ? "" : "; " + nameless + " ID without a name left out"));
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.filter(file -> file.toString().endsWith(".csv")).toList()) {
				if (!written.contains(file.getFileName().toString())) {
					Files.delete(file);
					report.add(file.getFileName() + ": deleted, as the client no longer has its namespace");
				}
			}
		}
		Files.writeString(folder.resolve(TABLES), String.join("\n",
				"# The namespaces of the client's id, config and var tables, as the constants dump found them",
				"id " + String.join(" ", dump.namespaces()),
				"config " + String.join(" ", dump.config()),
				"var " + String.join(" ", dump.var())) + "\n", StandardCharsets.UTF_8);
		return report;
	}

	/** How a namespace's rows changed: the names added and removed, and those whose ID or columns changed. */
	private static String change(List<String> before, List<String> after) {
		Map<String, String> old = byName(before);
		Map<String, String> now = byName(after);
		long added = now.keySet().stream().filter(name -> !old.containsKey(name)).count();
		long removed = old.keySet().stream().filter(name -> !now.containsKey(name)).count();
		long changed = now.entrySet().stream()
				.filter(entry -> old.containsKey(entry.getKey()) && !old.get(entry.getKey()).equals(entry.getValue()))
				.count();
		return added + removed + changed == 0 ? "unchanged"
				: added + " added, " + removed + " removed, " + changed + " changed";
	}

	private static Map<String, String> byName(List<String> rows) {
		Map<String, String> byName = new HashMap<>();
		for (String row : rows) {
			List<String> cells = COMMA.splitAsStream(row).toList();
			if (cells.size() >= 2) {
				byName.put(cells.get(1), row);
			}
		}
		return byName;
	}

	/** The namespaces of id, config and var that constants/tables.txt names; empty for a folder without it. */
	static Map<String, List<String>> tables(Path folder) throws IOException {
		Path file = folder.resolve(TABLES);
		Map<String, List<String>> tables = new LinkedHashMap<>();
		if (!Files.exists(file)) {
			return tables;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (!line.isBlank() && !line.startsWith("#")) {
				List<String> words = SPACE.splitAsStream(line.strip()).toList();
				tables.put(words.get(0), words.subList(1, words.size()));
			}
		}
		return tables;
	}
}
