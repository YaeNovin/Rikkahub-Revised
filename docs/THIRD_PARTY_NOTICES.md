# Third-Party Notices

This document covers third-party browser assets copied into
`app/src/main/assets/html/`. These components are not relicensed as AGPL-3.0;
their original license terms continue to apply. The referenced license files
are also stored under `app/src/main/assets/html/licenses/` so they are included
in Android application packages.

## Bundled Assets

### Offline ECharts map data (2026-09-09)

`html/maps/china.json` and `html/maps/world.json` are unchanged data files from
the Apache ECharts 4.9.0 npm distribution, under its Apache-2.0 license (packaged
as `html/licenses/echarts-maps-4.9.0-Apache-2.0.txt`). The China file uses ECharts'
UTF8Encoding format; ECharts decodes it when registering the map. These are the
historical visualization boundaries supplied with that version, not a current
administrative boundary database. Explicit user-supplied geometry takes priority.

| Asset | Fixed source | SHA-256 |
| --- | --- | --- |
| `maps/china.json` | `https://cdn.jsdelivr.net/npm/echarts@4.9.0/map/json/china.json` | `d392f651a48e6213c9bfc83f406711069de296c17f426cffc0ad1148078ee226` |
| `maps/world.json` | `https://cdn.jsdelivr.net/npm/echarts@4.9.0/map/json/world.json` | `049b334579e5a42d5d16c72d014d380e048e39fc1504049f212acb589484d2fa` |

### Rendering libraries

| Local asset | Component/version | License | Immutable source | SHA-256 |
| --- | --- | --- | --- | --- |
| `mermaid.min.js` | Mermaid 10.9.8 | MIT | `https://cdn.jsdelivr.net/npm/mermaid@10.9.8/dist/mermaid.min.js` | `be7ad45eafe33b6753d65e0b020dbcf041dd7d54264053bd3f942d2ce01c823d` |
| `renderers/abcjs-basic-min.js` | ABCJS 6.2.2 | MIT | `https://cdn.jsdelivr.net/npm/abcjs@6.2.2/dist/abcjs-basic-min.js` | `059b1bcc67998ac89dd7fa3575aef49855ed31fd0a1d00354d34430436a91ae3` |
| `renderers/atom-one-dark.min.css` | highlight.js 11.9.0, Atom One Dark theme | BSD-3-Clause | `https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css` | `4237ffca7ce6aadb438c457e0a675b125c534bbdda5b87f41f3a1495603bcc9b` |
| `renderers/echarts.min.js` | Apache ECharts 5.5.0 | Apache-2.0; bundled d3 portions under BSD-3-Clause | `https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js` | `42f8329d989b6f6539dd2b15bbdf0d82025762ac112fbb60dc57b27d7bcf3946` |
| `renderers/highlight.min.js` | highlight.js 11.9.0 | BSD-3-Clause | `https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js` | `837a6fa5b0c736b52bbde2b2b6190f305da3fc9ed41681db5321507057b5c846` |
| `renderers/leaflet.css` | Leaflet 1.9.4 | BSD-2-Clause | `https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css` | `a7837102824184820dfa198d1ebcd109ff6d0ff9a2672a074b9a1b4d147d04c6` |
| `renderers/leaflet.js` | Leaflet 1.9.4 | BSD-2-Clause | `https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js` | `db49d009c841f5ca34a888c96511ae936fd9f5533e90d8b2c4d57596f4e5641a` |
| `renderers/railroad-diagrams.css` | Railroad Diagrams 1.0.0 | CC0-1.0 | `https://cdn.jsdelivr.net/npm/railroad-diagrams@1.0.0/railroad-diagrams.css` | `cbffdbb716d877d9cb2353dd57c95f47770fd6a770f827e1b999f2e9b81e9fb1` |
| `renderers/railroad-diagrams.js` | Railroad Diagrams commit 736dec7 | CC0-1.0 | `https://github.com/tabatkins/railroad-diagrams/blob/736dec7cc847530ab7d498b1a3331e61b1752ada/railroad.js` | `6fe4cf84a9bd9ca56edd1316923ff8b711a21d2c7d6d174d72fa345d95b977ec` |

The Mermaid file is derived from the official 10.9.8 distribution and differs
only in 18 whitespace bytes; all non-whitespace content is identical. The seven
other original assets retain the recorded fixed-version bytes. Railroad JS now
uses the pinned source commit above with a browser wrapper: ESM exports are
removed and public constructor factories are exposed globally, including Stack.
The stylesheet remains at 1.0.0 and the CC0 license continues to apply. The hash
in its row identifies the packaged wrapper, not the upstream source file.

## Copyright and Attribution

- Mermaid: Copyright (c) 2014-2022 Knut Sveidqvist.
- ABCJS: Copyright (c) 2009-2023 Paul Rosen and Gregory Dyke.
- Apache ECharts: Copyright 2017-2024 The Apache Software Foundation. It
  includes identified d3 portions, Copyright 2010-2016 Mike Bostock.
- highlight.js and the packaged Atom One Dark stylesheet: Copyright (c) 2006
  Ivan Sagalaev and contributors.
- Leaflet: Copyright (c) 2010-2023 Volodymyr Agafonkin and Copyright (c)
  2010-2011 CloudMade.
- Railroad Diagrams: Tab Atkins Jr. and contributors dedicated the project
  files to the public domain under CC0-1.0.

## Packaged License Files

- `licenses/mermaid-10.9.8-MIT.txt`
- `licenses/abcjs-6.2.2-MIT.txt`
- `licenses/echarts-5.5.0-Apache-2.0.txt`
- `licenses/echarts-5.5.0-NOTICE.txt`
- `licenses/echarts-5.5.0-d3-BSD-3-Clause.txt`
- `licenses/highlight.js-11.9.0-BSD-3-Clause.txt`
- `licenses/leaflet-1.9.4-BSD-2-Clause.txt`
- `licenses/railroad-diagrams-1.0.0-CC0-1.0.txt`

The Gradle dependency graph is not enumerated in this static-asset notice.
Before distributing an APK/AAB, generate and review a dependency-license report
for the exact release variant and preserve any additional notices required by
those dependencies.

## Additional renderer assets (2026-09-09)

The user-supplied files below are copied without changes; `index.min.js` is
renamed to `graphviz.min.js` to identify its module. Versions were checked from
bundle exports/headers. `SmilesDrawer.Version` is 2.4.1; its bundled chroma code
also contains a separate 2.4.2 version string.

| Local asset under `html/renderers` | Component | License | SHA-256 |
| --- | --- | --- | --- |
| `graphviz.min.js` | @hpcc-js/wasm-graphviz 1.29.0, embedded Graphviz 16.1.0 | Apache-2.0 wrapper; EPL-2.0 Graphviz | `ba9b08b9a8c96613075c7b4f6c49512e04ddc6ce2e9cc20675837b976fe6caab` |
| `wavedrom.min.js` | WaveDrom 3.1.0 | MIT | `eba17f2bd0e72ba737c65f5488a75657d3592f6b0a59aff2a43870439ae6fee5` |
| `vega-embed.js` | Vega Embed 7.2.0 | BSD-3-Clause | `b69eac2846a0061683b7e03501790fb0bcbdb851c797c6baf3417c9d8852819e` |
| `smiles-drawer.js` | SmilesDrawer 2.4.1 | MIT; embedded chroma notices preserved in bundle | `6b0397cd52a708eeafb995f191d6d972449377e5603ae603cb210f537666fddc` |
| `opensheetmusicdisplay.js` | OpenSheetMusicDisplay 2.1.2 | BSD-3-Clause; dependencies retain their licenses | `888b744196665ab10dba7ee311784deea9e1c545cf60b6089d455afec6b50a3f` |
| `jquery.min.js` | jQuery 3.6.4, supplied utility, not loaded by renderers | MIT | `a0fe8723dcf55da64d06b25446d0a8513e52527c45afcb37073465f9c6f352af` |

Required fixed-version dependencies fetched from official npm distributions:

| Asset | Source | SHA-256 |
| --- | --- | --- |
| `wavedrom-skin.js` | `https://cdn.jsdelivr.net/npm/wavedrom@3.1.0/skins/default.js` | `7ddb9cfd9b339397b35b0b36b889d8fc02d656622b995e954e05dbb184917852` |
| `vega.min.js` | `https://cdn.jsdelivr.net/npm/vega@6.4.0/build/vega.min.js` | `8f6a3587cf8d4f42c7e08120e3eb05d067e746d554e39d2dcf52acc0bd5ba28f` |
| `vega-lite.min.js` | `https://cdn.jsdelivr.net/npm/vega-lite@6.4.3/build/vega-lite.min.js` | `35a9821df838825b05a6a73e9414b58747a1b18321583858ed903c66393a5c7e` |

License texts are packaged in `html/licenses`: `wavedrom-3.1.0.txt`,
`graphviz-wasm-1.29.0.txt`, `graphviz-16.1.0-EPL-2.0.txt`, `vega-6.4.0.txt`,
`vega-lite-6.4.3.txt`, `vega-embed-7.2.0.txt`, `smiles-drawer-2.4.1.txt`,
`opensheetmusicdisplay-2.1.2.txt`, `jquery-3.6.4.txt`, `jszip-3.10.1.txt`,
`vexflow-1.2.93.txt`, `loglevel.txt` and `typescript-collections-1.3.3.txt`.
Graphviz's corresponding source is available at
`https://gitlab.com/graphviz/graphviz/-/tree/16.1.0`; the wrapper source is
`https://github.com/hpcc-systems/hpcc-js-wasm` and npm package
`@hpcc-js/wasm-graphviz@1.29.0`. The repository's small `graphviz-worker.js`
adapter is separate from these unmodified vendor assets.

## Offline whole-message HTML preview

All URLs in this table are fixed npm package distributions under
`https://cdn.jsdelivr.net/npm/`. Scripts/styles live in `html/renderers`.

| Asset | Source suffix | License | SHA-256 |
| --- | --- | --- | --- |
| `markdown-it.min.js` | `markdown-it@14.0.0/dist/markdown-it.min.js` | MIT | `bcdac8ec17bb3afb710adf35a50162c28b8f7d6ebfd5d7e59b808002a1391995` |
| `markdown-it-task-lists.min.js` | `markdown-it-task-lists@2.1.1/dist/markdown-it-task-lists.min.js` | ISC | `4f3b23f41bb3787957da2602fbccc4df0d017928c1fce62583159e096b832a81` |
| `katex.min.js` | `katex@0.16.8/dist/katex.min.js` | MIT | `d4f0ea25c4ccb7986b229d6427794b7063cf3209b0376cf5a64bfc4dd7918c95` |
| `mhchem.min.js` | `katex@0.16.8/dist/contrib/mhchem.min.js` | bundled notices retained | `f0ca03df194b8c3d6017ff455db6a0ef98857905663fa311a6cded788b15340b` |
| `markdown-it-katex.source.js` | `@vscode/markdown-it-katex@1.1.2/dist/index.js` | MIT | `27f34d152f9773e5f7200effaab9157bbd2669409e85a0a5faa86ff85c05f1f9` |

`katex.min.css` and all 60 referenced files under `fonts/` come from the same
KaTeX 0.16.8 distribution. License files are packaged under `html/licenses`:
`markdown-it-14.0.0.txt`, `markdown-it-task-lists-2.1.1.txt`, `katex-0.16.8.txt`
and `markdown-it-katex-1.1.2.txt`.

`markdown-it-katex.js` is generated from the preserved source with esbuild
0.25.12, `--bundle --format=iife --global-name=markdownItKatex --target=chrome74`
and `--alias:katex=./scripts/katex-browser-global.cjs`. The adapter shares the
page's KaTeX instance so the mhchem extension remains available. Its SHA-256 is
`a3beaaf7c426689b8d600f266ef6bb70591a38e8a2cbdd0a14573955eacf653e`.
