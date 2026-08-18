# Third-Party Notices

This document covers third-party browser assets copied into
`app/src/main/assets/html/`. These components are not relicensed as AGPL-3.0;
their original license terms continue to apply. The referenced license files
are also stored under `app/src/main/assets/html/licenses/` so they are included
in Android application packages.

## Bundled Assets

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
| `renderers/railroad-diagrams.js` | Railroad Diagrams 1.0.0 | CC0-1.0 | `https://cdn.jsdelivr.net/npm/railroad-diagrams@1.0.0/railroad-diagrams.js` | `5d6a6210c57aa24965edd5f9e4f02415e624f922d107246de9639244b9d16c47` |

The Mermaid file is derived from the official 10.9.8 distribution and differs
only in 18 whitespace bytes; all non-whitespace content is identical. The other
eight files are byte-for-byte identical to the fixed-version sources above.

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
