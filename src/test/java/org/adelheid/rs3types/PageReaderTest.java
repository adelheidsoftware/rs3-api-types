package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** What the reader makes of the tests' pages. */
class PageReaderTest {

	private final List<String> reports = new ArrayList<>();
	private final ApiPage page = PageReader.read("core/lantern.html", Fixtures.read("lantern.html"), Facts.parse(""),
			reports);
	private final ApiPage.TypeDoc lantern = page.types().get(0);

	@Test
	void readsTheTypeFromTheTitleAndTheOverview() {
		assertEquals("Lantern", lantern.name());
		assertEquals("Light", lantern.parent());
		// The overview's note joins the description, and so does the section of prose, History
		assertEquals(List.of("A lantern that a `Lamplighter` lights, for the tests.", "Info: A note in the overview.",
				"History: Lanterns are old."), lantern.description());
	}

	@Test
	void mergesTheFormsOfAFunctionUnderSeveralHeadings() {
		ApiPage.FunctionDoc constructor = lantern.functions().get(0);
		assertEquals("new", constructor.name());
		assertFalse(constructor.colon());
		assertEquals(List.of("Int", "Lantern"),
				constructor.forms().stream().map(form -> form.params().get(0).type()).toList());
		assertEquals(List.of(new ApiPage.ParamDoc("brightness", "Int", true, "How bright, from 1 to 5.")),
				constructor.params());
	}

	@Test
	void readsAMethodsDescriptionNotesAndTables() {
		ApiPage.FunctionDoc light = lantern.functions().get(1);
		assertEquals("Light", light.name());
		assertTrue(light.colon());
		assertEquals(List.of("Lights the lantern.", "Warning: It burns `oil`."), light.description());
		assertEquals(List.of(new ApiPage.ReturnDoc("Boolean", "Whether it lit.")), light.returns());
	}

	@Test
	void givesEachNameOfASignatureBlockAFunction() {
		assertEquals(List.of("new", "Light", "SetColour", "GetOil", "GetOilLevel"),
				lantern.functions().stream().map(ApiPage.FunctionDoc::name).toList());
		assertEquals(2, lantern.functions().get(2).forms().size());
		assertEquals(List.of("The oil left."), lantern.functions().get(4).description());
	}

	@Test
	void learnsTheOtherNameTheSignaturesGiveTheType() {
		assertEquals(Map.of("Lamp", "Lantern"), page.otherNames());
	}

	@Test
	void readsPropertiesAndEnumerations() {
		assertEquals(List.of("brightness", "tags", "owner"),
				lantern.properties().stream().map(ApiPage.PropertyDoc::name).toList());
		assertEquals("[ String ]", lantern.properties().get(1).type());
		assertEquals(List.of("LanternState", "LanternGrants"),
				page.enums().stream().map(ApiPage.EnumDoc::name).toList());
		assertEquals(new ApiPage.EnumValue("LIT", "1", "Lit"), page.enums().get(0).values().get(1));
		assertEquals(new ApiPage.EnumValue("lightAccess", null, "Allows lighting"),
				page.enums().get(1).values().get(0));
		assertEquals(List.of(), reports);
	}

	@Test
	void aPageOfProseDocumentsNoApi() {
		assertFalse(PageReader.read("guide.html", Fixtures.read("guide.html"), Facts.parse(""), new ArrayList<>())
				.isApi());
		assertTrue(page.isApi());
	}

	@Test
	void reportsWhatOnlyAFactExplains() {
		List<String> lampReports = new ArrayList<>();
		PageReader.read("core/lamps.html", Fixtures.read("lamps.html"), Facts.parse(""), lampReports);
		assertEquals(List.of(
				"core/lamps.html#trim: a function without a signature, Trim",
				"core/lamps.html#dim: the heading Dim documents a function its signatures call Brighten; a rename fact"
						+ " says which is right",
				"core/lamps.html#properties: a field whose type the docs do not give, glow; a field fact gives it"),
				lampReports);
	}

	@Test
	void readsTypesUnderHeadingsOfTheirOwn() {
		ApiPage lamps = PageReader.read("core/lamps.html", Fixtures.read("lamps.html"), Fixtures.facts(),
				new ArrayList<>());
		assertEquals(List.of("Lamps", "lamps.colours", "LampsFindSettings", "lamps.Wick", "LampHolder", "LampShade"),
				lamps.types().stream().map(ApiPage.TypeDoc::name).toList());
		// A type its heading does not name takes its signatures' name; their placeholder T, which no member names,
		// types nothing
		assertEquals(List.of(), lamps.types().get(4).generics());
		assertEquals(List.of("lightAll"), lamps.globals().stream().map(ApiPage.FunctionDoc::name).toList());
		assertEquals(List.of("lamps.Hook", "lamps.Flicker"),
				lamps.enums().stream().map(ApiPage.EnumDoc::name).toList());
		assertEquals(List.of("A glow, which lamps give off."), lamps.described().get("lamps.Glow"));
	}

	@Test
	void keepsTheTypeParametersAMemberNames() {
		assertEquals(List.of("T"), PageReader.namedGenerics(List.of("T", "U"), List.of("Int", "Table<T>")));
		assertEquals(List.of(), PageReader.namedGenerics(List.of("T"), List.of("Tile", "String")));
	}

	@Test
	void readsEvents() {
		ApiPage events = PageReader.read("core/events.html", Fixtures.read("events.html"), Fixtures.facts(),
				new ArrayList<>());
		assertEquals(List.of("LanternLit", "Tick", "LanternDraw"),
				events.events().stream().map(ApiPage.EventDoc::name).toList());
		assertEquals(null, events.events().get(1).payload());
		assertEquals("boolean", events.events().get(2).returns());
		assertEquals(List.of(), events.types());
	}

	@Test
	void writesAFootnoteInPlaceOfItsNumber() {
		ApiPage.TypeDoc dye = read("""
				<h1 id="dye">Dye</h1>
				<h2 id="properties">Properties</h2>
				<table>
				<thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead>
				<tbody><tr><td>colour</td><td>Int</td><td>Its colour.<sup id="fnref:1">\
				<a class="footnote-ref" href="#fn:1">1</a></sup></td></tr></tbody>
				</table>
				<div class="footnote"><hr /><ol><li id="fn:1">
				<p>Packed into 16 bits:</p>
				<ul><li>Hue: <code>6</code></li><li>Lightness: <code>7</code></li></ul>
				<p>As the client packs it.&#160;<a class="footnote-backref" href="#fnref:1">&#8617;</a></p>
				</li></ol></div>
				""", "").types().get(0);
		assertEquals(List.of(new ApiPage.PropertyDoc("colour", "Int",
				"Its colour. Packed into 16 bits: Hue: `6`; Lightness: `7`. As the client packs it.")),
				dye.properties());
		assertEquals(List.of(), reports);
	}

	@Test
	void marksEachFieldOfATableItsNoteSaysIsReadOnly() {
		ApiPage.TypeDoc sprite = read("""
				<h1 id="sprite">Sprite</h1>
				<h2 id="properties">Properties</h2>
				<div class="admonition info"><p class="admonition-title">Info</p>
				<p>All properties are read-only.</p></div>
				<table>
				<thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead>
				<tbody>
				<tr><td>sprite</td><td>Int</td><td><a href="#runtimesprite">RuntimeSprite</a></td></tr>
				<tr><td>parent</td><td>Sprite?</td><td><strong>(Read-Only)</strong> Its parent.</td></tr>
				</tbody>
				</table>
				""", "").types().get(0);
		// The second type of sprite stands where its description goes, and parent spells its mark otherwise
		assertEquals(List.of(new ApiPage.PropertyDoc("sprite", "Int/RuntimeSprite", "(Read-only)"),
				new ApiPage.PropertyDoc("parent", "Sprite?", "(Read-only) Its parent.")), sprite.properties());
	}

	@Test
	void readsTheReadOnlyMarkPastAStrayAsterisk() {
		ApiPage.TypeDoc carousel = read("""
				<h1 id="carousel">Carousel</h1>
				<h2 id="properties">Properties</h2>
				<table>
				<thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead>
				<tbody>
				<tr><td>isSwitching</td><td>Bool</td><td>*<strong>(Read-only)</strong><br/>Whether it switches.</td></tr>
				</tbody>
				</table>
				""", "").types().get(0);
		assertEquals(List.of(new ApiPage.PropertyDoc("isSwitching", "Bool", "(Read-only) Whether it switches.")),
				carousel.properties());
	}

	@Test
	void addsTheAliasesANoteListsAsFields() {
		ApiPage.TypeDoc colour = read("""
				<h1 id="colour">Colour</h1>
				<h2 id="properties">Properties</h2>
				<table>
				<thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead>
				<tbody>
				<tr><td>x</td><td>Float</td><td>First element.</td></tr>
				<tr><td>y</td><td>Float</td><td>Second element.</td></tr>
				<tr><td>u</td><td>Float</td><td>First element, as u.</td></tr>
				</tbody>
				</table>
				<div class="admonition info"><p class="admonition-title">Info</p>
				<p>The following aliases are supported:</p>
				<ul><li>u/v -&gt; x/y</li><li>w -&gt; z</li></ul></div>
				""", "").types().get(0);
		assertEquals(List.of("x", "y", "u", "v"), colour.properties().stream().map(ApiPage.PropertyDoc::name).toList());
		assertEquals(new ApiPage.PropertyDoc("v", "Float", "Second element, aliasing y."), colour.properties().get(3));
		assertEquals(List.of("core/case.html#properties: an alias w of z, a field the type does not have"), reports);
	}

	@Test
	void givesEachTypeThatExtendsACallerTypeItsFunctions() {
		List<ApiPage.TypeDoc> types = read("""
				<h1 id="vectors">Vectors</h1>
				<h2 id="vector">Vector</h2>
				<h3 id="methods">Methods</h3>
				<h4 id="min">Min</h4>
				<div class="language-lua highlight"><pre><code>function Vector:Min(other : Vector) -&gt; Vector
				</code></pre></div>
				<h4 id="length">Length</h4>
				<div class="language-lua highlight"><pre><code>function Vector:Length() -&gt; Float
				</code></pre></div>
				<h2 id="vector2">Vector2</h2>
				<h3 id="methods_1">Methods</h3>
				<h4 id="length_1">Length</h4>
				<div class="language-lua highlight"><pre><code>function Vector2:Length() -&gt; Int
				</code></pre></div>
				""", "parent Vector2 Vector\ncaller Vector\n").types();
		ApiPage.TypeDoc vector2 = types.get(1);
		assertEquals("Vector2", vector2.name());
		// Its own Length stays; Min is Vector's, of a Vector2
		assertEquals(List.of("Length", "Min"), vector2.functions().stream().map(ApiPage.FunctionDoc::name).toList());
		assertEquals("Int", vector2.functions().get(0).forms().get(0).returns().get(0));
		assertEquals(new Signature("Vector2", "Min", true, List.of(new Signature.Param("other", "Vector2")),
				List.of("Vector2")), vector2.functions().get(1).forms().get(0));
	}

	@Test
	void writesAParameterOfAnyTypeAsOneToGive() {
		ApiPage enumPage = read("""
				<h1 id="enum">Enum</h1>
				<h2 id="methods">Methods</h2>
				<h3 id="getoutputvalue">GetOutputValue</h3>
				<div class="language-lua highlight"><pre><code>function Enum:GetOutputValue(inputValue : ?) -&gt; ?
				</code></pre></div>
				""", "");
		String lua = LuaCatsWriter.write(enumPage, "", Map.of(), reports, new TreeMap<>(), Set.of());
		assertTrue(lua.contains("---@param inputValue any\n"), lua);
		assertTrue(lua.contains("---@return any\n"), lua);
	}

	/** A page of the tests' own, of an article alone. */
	private ApiPage read(String article, String facts) {
		return PageReader.read("core/case.html", "<html><body><article>" + article + "</article></body></html>",
				Facts.parse(facts), reports);
	}
}
