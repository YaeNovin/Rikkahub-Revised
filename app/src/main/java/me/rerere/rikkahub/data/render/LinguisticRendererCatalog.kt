package me.rerere.rikkahub.data.render

private const val COMMON = "Rikka linguistic transport schema v1 (not an automatic analyser). Strict JSON with version:1. Optional title,id,source (plain attribution),language (BCP-47),dir:ltr|rtl|auto. All labels are literal text; no HTML/scripts/URLs loaded. Use the dedicated fence or render_content. Disabled renderers retain source. "
internal val linguisticRendererSpecs = listOf(
    RendererSpec("ipa", "IPA transcription", "Versioned JSON; Unicode IPA preserved verbatim",
        """{"version":1,"language":"en","notation":"phonetic","text":"ˈlɪŋ.ɡwɪs.tɪks t͡ʃ ãː"}""",
        setOf("ipa", "phonetic"), MAX_LINGUISTIC_SOURCE_CHARS,
        notes = COMMON + "Required text (max 4000 characters). All displayed IPA uses /…/; legacy notation values remain accepted. For prose without fences use <ipa>text</ipa> or /distinctive IPA symbols/. Dedicated bundled Doulos SIL font; combines marks without compatibility normalization. No pronunciation/audio generation."),
    RendererSpec("ruby_annotation", "Ruby pronunciation / 注音", "Versioned JSON, controlled HTML Ruby semantics",
        """{"version":1,"language":"ja","segments":[{"base":"日本語","reading":"にほんご"},{"base":"を学ぶ。"}],"translation":"学习日语。"}""",
        setOf("ruby_annotation", "ruby-annotation", "furigana", "zhuyin"), MAX_LINGUISTIC_SOURCE_CHARS,
        notes = COMMON + "segments:1..128 objects {base,reading?}, each <=120 characters; omit reading for unannotated text. Optional translation, writingMode:horizontal-tb|vertical-rl|vertical-lr, rubyPosition:over|under|inter-character. Inline prose can use <ruby>base<rt>reading</rt></ruby> without fences. No multilevel Ruby or automatic pronunciation generation."),
    RendererSpec("gloss", "Interlinear word / morpheme gloss", "Versioned JSON following Leipzig alignment rules",
        """{"version":1,"language":"tr","id":"1","words":[{"text":"evler","morphemes":"ev-ler","gloss":"house-PL"},{"text":"güzel","morphemes":"güzel","gloss":"beautiful"}],"translation":"The houses are beautiful.","legend":{"PL":"plural"}}""",
        setOf("gloss", "interlinear", "igt"), MAX_LINGUISTIC_SOURCE_CHARS,
        notes = COMMON + "words:1..128 objects {text,morphemes?,gloss}. Required translation; optional legend:{abbreviation:meaning}. One object per word, no whitespace alignment. Source and gloss -/= boundaries must correspond (Leipzig rules 1/2); if morphemes supplied, include them for every word. Dot bundles e.g. 3SG.PST are allowed. Word units wrap together, rows remain vertically aligned; not linguistic analysis validation."),
    RendererSpec("syntax_tree", "Constituency syntax tree", "Versioned bounded tree JSON",
        """{"version":1,"root":{"label":"S","children":[{"label":"NP","children":[{"label":"I"}]},{"label":"VP","children":[{"label":"study"},{"label":"linguistics"}]}]}}""",
        setOf("syntax_tree", "syntax-tree", "constituency"), MAX_LINGUISTIC_SOURCE_CHARS,
        notes = COMMON + "Required root:{label,children?}; child has same shape. <=128 nodes, depth<=16, <=32 children/node, labels<=80 characters. App-specific transport schema, not Penn Treebank input. Children retain order; long trees scroll horizontally, never shrink text to fit."),
    RendererSpec("dependency", "UD dependency arcs", "CoNLL-U text: ten TAB-separated columns",
        "# text = I study.\n1\tI\tI\tPRON\t_\t_\t2\tnsubj\t_\t_\n2\tstudy\tstudy\tVERB\t_\t_\t0\troot\t_\tSpaceAfter=No\n3\t.\t.\tPUNCT\t_\t_\t2\tpunct\t_\t_\n",
        setOf("dependency", "conllu", "conll-u", "ud"), MAX_LINGUISTIC_SOURCE_CHARS,
        notes = "Universal Dependencies CoNLL-U, NFC Unicode. Raw text, not JSON; ten TAB fields; blank lines separate sentences. 1..8 sentences, <=80 integer words/100 rows/240 arcs per sentence. Basic HEAD tree must have sequential integer IDs, one root and no cycles. Multiword ranges and empty nodes retained; enhanced DEPS rendered dashed alongside solid basic dependencies. All ten columns/comments remain inspectable. Word order is fixed, arrows point from head to dependent; ROOT is virtual. No automatic parsing or syntax correctness guarantee."),
)
