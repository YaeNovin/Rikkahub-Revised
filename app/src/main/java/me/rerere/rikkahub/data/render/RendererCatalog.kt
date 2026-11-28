package me.rerere.rikkahub.data.render

/** Shared catalog for model discovery, tool validation and UI language aliases.
 * Native Markdown features (tables, images/GIF, links, tasks) belong to markdown. */
internal data class RendererSpec(
    val id: String,
    val title: String,
    val format: String,
    val example: String,
    val aliases: Set<String> = setOf(id),
    val maxChars: Int = 128 * 1024,
    val notes: String = "",
    val automatic: Boolean = true,
)

internal object RendererCatalog {
    const val version = 10
    private const val curveNotes = " Shared curve layer: {type:curve,formula:hypotrochoid|epitrochoid|lissajous|harmonic,radius:80}. " +
        "Default formula hypotrochoid. Trochoids: fixed integer 1..24 (default 7), rolling integer 1..12 (default 3), " +
        "pen 0..2 (default .7, distance/rolling radius); hypotrochoid requires fixed>rolling. " +
        "Lissajous: frequencyX/frequencyY integers 1..16 (defaults 3/2), phase degrees -360..360 (default 90). " +
        "Harmonic: optional terms (1..6) with frequency integer 1..16, amplitude -0.8 to 0.8, phase degrees -360..360; " +
        "defaults frequency=6,amplitude=.15,phase=0 per term, total absolute amplitude <=.9; omitted terms use 6/.2 and 12/.08. " +
        "Only the selected formula's fields are accepted. Curve layers accept shared style, fill and background; " +
        "generated closed paths fit within radius. Fixed app-owned formulas and sampling, no expression/script/samples fields; " +
        "<=24000 curve vertices after symmetry copies. " +
        "Example layer: {\"type\":\"curve\",\"formula\":\"hypotrochoid\",\"fixed\":7,\"rolling\":3,\"pen\":0.7,\"radius\":80}."
    val entries = musicRendererSpecs + linguisticRendererSpecs + listOf(
        RendererSpec("annotated_text", "Annotated literary / linguistic prose", "Controlled inline HTML and TEI critical-apparatus subset; no code fence",
            """<vertical lang="ja"><ruby>古語<rt>こご</rt></ruby>の文。<app><lem>春</lem><rdg wit="#A">花</rdg><note>異文</note></app></vertical>""",
            setOf("annotated_text", "literature", "critical_apparatus"), maxChars = 32768,
            notes = "Use directly in Markdown prose or this tool, without fences. ruby/rt/rp, <ipa>Unicode transcription</ipa> displayed /…/, <vertical lang=ja> for vertical-rl, tcy for short upright numbers; <syntax-tree>schema-v1 tree JSON</syntax-tree> for explicit unfenced diagrams. TEI-inspired app/lem/rdg wit, choice/sic/corr/orig/reg, note n, lb, gap extent, supplied, unclear are a bounded subset, not a full TEI edition or automatic reading/transcription. Preserve archaic kana/iteration marks. No arbitrary CSS, author JS or remote resources. Click lemmas/notes to expand local apparatus. Ruby, IPA and tree settings remain independent; literary setting controls vertical text and apparatus."),
        RendererSpec("pattern", "p5.js decorative patterns", "Strict JSON preset layers; not p5 JavaScript", """{"title":"星瓣曼陀罗","seed":42,"layers":[{"type":"mandala","petals":12,"rings":4,"radius":86},{"type":"rose","numerator":5,"radius":32,"color":"#fbbf24"},{"type":"ring","radius":94}]}""", setOf("pattern", "generative_art", "p5_pattern", "p5-pattern"), maxChars = 32768,
            notes = "Entertainment decorative preset only; strict JSON, never p5 code. Top-level: title/name, background/backgroundColor, color/lineColor, palette/colors (1..12 hex colors), seed, glow (0..3 or {amount,color,spread,pulse}), animate, animation, layers (1..12; 24000-vertex budget). Animation modes: rotate|pulse|draw|sequence|flicker|orbit, with bounded duration/delay/stagger/easing/loop/amplitude/frequency/phase. Shared layer fields: type,radius (1..96),color/lineColor/colors,width (.25..5),opacity (.05..1),rotation,speed,symmetry (1..12),mirror,lineCap,lineJoin,animation. Types: mandala,rose,fractal plus sigil ring/circle/star/hexagram/polygon/ticks/rays/arc/dot/orbit; path/freeform accepts 2..256 bounded points, closed and solid fill. Mandala: petals 3..32, rings 1..6; rose uses numerator/denominator/phase; fractal variants koch|sierpinski|tree with bounded depth. Entertainment only, no educational algorithms or physics. Shared effects: stardust, trail, reveal; bounded and adaptive, no model-controlled hardware budget. No JavaScript, shaders, URLs, custom recursions or p5 sketches."),
        RendererSpec("math_plot", "JSXGraph math plot", "Strict JSON with mathematical expressions, not JavaScript", "{\"xRange\":[-8,8],\"yRange\":[-3,3],\"parameters\":[{\"name\":\"a\",\"min\":0,\"max\":3,\"value\":1}],\"curves\":[{\"expression\":\"a*sin(x)\",\"label\":\"a sin(x)\"}]}", setOf("math_plot", "math_plan", "math-plot", "jsxgraph"), maxChars = 32768,
            notes = "JSON fields: title, xRange/yRange [min,max], equalAspect/grid booleans, parameters (max 4: name,label?,min,max,value,step), Off-grid initial values are preserved by using a continuous slider. curves (max 8), primitives (max 32). Curves: {type:function,expression,range?,label?,color?}, {type:parametric,x,y,range:[t0,t1]}, {type:polar,r,range:[theta0,theta1]}. Variables: x, t, theta respectively, plus named parameters. Constants pi,e; operators + - * / ^; functions sin cos tan asin acos atan atan2 sqrt abs exp ln log log10 floor ceil round min max pow. Use explicit * multiplication. Primitive forms: {type:point,at:[x,y],label?}, {type:segment|vector,from:[x,y],to:[x,y],label?}, {type:circle,center:[x,y],radius,label?}. Colors are hex. Numerical sampling only, not a symbolic solver or general physics engine. jsxgraph is a JSON alias only; raw JSXGraph JavaScript is displayed as source, never executed. No implicit curves/3D or differential equation solver in this phase."),
        RendererSpec("sigil", "Parametric sigil", "Strict JSON layers rendered as SVG", "{\"title\":\"六芒星\",\"color\":\"#7c3aed\",\"layers\":[{\"type\":\"ring\",\"radius\":90},{\"type\":\"ticks\",\"radius\":84,\"count\":48},{\"type\":\"star\",\"points\":6,\"step\":2,\"radius\":67,\"speed\":8}]}", setOf("sigil", "magic_circle", "magic-circle"), maxChars = 32768,
            notes = "Strict JSON layers rendered as SVG. Top-level: title/name/description/style/theme, background/backgroundColor, color/lineColor/colors/palette, glow (0..3 or {amount,color,spread,pulse}), animate, animation, effects, layers (1..24; <=1500 estimated elements). Shared layer fields: type,radius (1..96),background (optional solid backplate disk),color/lineColor/colors,width,opacity,rotation,speed,symmetry (1..12),mirror,lineCap,lineJoin,animation. Types: ring/circle; star/hexagram (points 3..32, step < points/2); polygon; ticks/rays; arc; dot/orbit; text_ring (text <=96 chars,fontSize 2..10); path/freeform (2..256 bounded points, closed, solid fill). Six-point hexagram normalizes to star points=6 step=2. Animation modes: rotate|pulse|draw|sequence|flicker|orbit; respects visibility and reduced motion. Shared bounded effects: stardust, trail, reveal. No HTML, SVG d paths, URLs or scripts; no model-controlled hardware budget."),
        RendererSpec("markdown", "Markdown", "Markdown text, tables, images/GIF, links, lists and task lists", "# Summary\n\n- [x] Complete", notes = "Raw HTML is shown as source; images use the existing attachment/URL renderer."),
        RendererSpec("latex", "LaTeX", "A math formula without delimiters", "x^2+y^2=z^2", maxChars = 8000, notes = "Native JLaTeXMath subset, not all KaTeX/LaTeX macros."),
        RendererSpec("code", "Source code", "Source text; optional language for syntax highlighting", "print(42)", notes = "Display only; never runs the source or infers a visual renderer."),
        RendererSpec("diff", "Diff", "Unified diff/patch", "--- a.txt\n+++ a.txt\n@@ -1 +1 @@\n-old\n+new", setOf("diff", "patch", "udiff", "unified-diff", "git-diff")),
        RendererSpec("html", "HTML (manual, static)", "HTML document or fragment", "<h2>Hello</h2><p>Static preview</p>", notes = "Source first. Only the user can open a sanitized static preview. No author scripts, handlers, frames, forms, navigation or external resources.", automatic = false),
        RendererSpec("svg", "SVG", "SVG XML", "<svg viewBox='0 0 100 100'><circle cx='50' cy='50' r='40' fill='teal'/></svg>", notes = "Sanitized graphics only; no scripts, event handlers, foreignObject or external resources."),
        RendererSpec("mermaid", "Mermaid", "Mermaid diagram source", "flowchart LR; A --> B", notes = "Strict Mermaid mode; no arbitrary HTML/JS."),
        RendererSpec("echarts", "ECharts", "Declarative chart options", "{\"xAxis\":{\"data\":[\"A\",\"B\"]},\"yAxis\":{},\"series\":[{\"type\":\"bar\",\"data\":[2,4]}]}", setOf("echarts", "chart"), notes = "JSON/object literals, not executable JavaScript callbacks."),
        RendererSpec("abc", "ABC music", "ABC notation", "X:1\nK:C\nC D E F|", setOf("abc", "abcjs"), maxChars = 128 * 1024, notes = "Offline abcjs; max 8000 note/rest events and 384 systems. Double-quoted chord symbols above notes, e.g. \"G7\"GABc. Complete-system pagination and whole-score vector PDF export; at most 64 pages."),
        RendererSpec("jianpu", "Numbered notation", "Numbered music notation", "1 2 3 4 | 5 6 7 1'", setOf("jianpu", "numbered", "numbered-notation", "numberednotation", "numbered_notation", "简谱"), maxChars = MAX_SCORE_SOURCE_CHARS, notes = "1600 tokens maximum. H: C | G7 after each melody line adds one chord/functional-analysis label per measure (24 characters per label). Lyrics use L:. Complete-system pagination and whole-score PDF export; 64 pages maximum."),
        RendererSpec("leaflet", "Map / GeoJSON", "GeoJSON or map config", "{\"markers\":[{\"lat\":30,\"lng\":120,\"popup\":\"Point\"}]}", setOf("leaflet", "map", "geojson"), notes = "Offline overview map by default; explicit tileUrl can use the network. Popup labels are plain text."),
        RendererSpec("railroad", "Railroad diagram", "EBNF or declarative railroad structure", "{\"type\":\"sequence\",\"items\":[\"hello\",\"world\"]}", setOf("railroad", "railroad-diagram", "grammar", "ebnf")),
        RendererSpec("wavedrom", "WaveDrom", "WaveJSON", "{\"signal\":[{\"name\":\"clk\",\"wave\":\"p....\"}]}", setOf("wavedrom", "waveform", "timing", "digital-waveform"), maxChars = 32000),
        RendererSpec("graphviz", "Graphviz", "DOT source", "digraph G { A -> B; }", setOf("graphviz", "dot"), maxChars = 32000, notes = "Needs system WebView WebAssembly/module Worker; layout timeout 15 seconds."),
        RendererSpec("vega", "Vega / Vega-Lite", "Declarative Vega or Vega-Lite JSON", "{\"mark\":\"bar\",\"data\":{\"values\":[{\"x\":\"A\",\"y\":2}]},\"encoding\":{\"x\":{\"field\":\"x\",\"type\":\"nominal\"},\"y\":{\"field\":\"y\",\"type\":\"quantitative\"}}}", setOf("vega", "vega-lite", "vegalite"), notes = "Use data.values; no external datasets or image URLs."),
        RendererSpec("smiles", "Molecule", "A SMILES string", "CC(=O)Oc1ccccc1C(=O)O", setOf("smiles", "smiles-drawer", "molecule"), maxChars = 4000),
        RendererSpec("musicxml", "MusicXML", "Uncompressed score-partwise XML", "<score-partwise version='3.1'><part-list><score-part id='P1'><part-name>Piano</part-name></score-part></part-list><part id='P1'><measure number='1'><attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note></measure></part></score-partwise>", setOf("musicxml", "music-xml"), maxChars = MAX_SCORE_SOURCE_CHARS, notes = "MusicXML 3/4 score-partwise. Up to 24 parts, 512 measures per part, 16000 note/rest events, 64 pages. Root/kind harmonies and MusicXML 4 numeral functional analysis; numeral is displayed as a timed standard direction for OSMD compatibility, original XML is retained. A4 pagination, whole-score vector PDF. XML/MusicXML/MXL attachments (2 MiB compressed and expanded budgets) can be opened locally. No external images/DTD entities, score-timewise or audio playback."),
        RendererSpec("terminal", "Read-only xterm", "Text/ANSI transcript", "\\x1b[32mSUCCESS\\x1b[0m 完成", setOf("terminal", "ansi", "xterm", "console-output"), notes = "Read-only, 2000 scrollback lines, no input/PTY/shell/network/OSC controls."),
    ).map { renderer ->
        if (renderer.id == "sigil" || renderer.id == "pattern") renderer.copy(notes = renderer.notes + curveNotes)
        else renderer
    }

    fun find(name: String): RendererSpec? = entries.firstOrNull { name.trim().lowercase() in it.aliases }
}
