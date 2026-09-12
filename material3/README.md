# Material 3 color utilities

The Kotlin sources under `material-color-utilities/kotlin` are the Android
build source of truth for this module. The sibling `java`, `cpp`, `dart`,
`swift`, and `typescript` directories are upstream reference copies and are
not compiled by the Android Gradle source set.

Dynamic color schemes are cached with a small access-ordered cache. Theme
generation is pure and can be requested during Compose recomposition, so the
bounded cache avoids repeating HCT and tonal-palette work without retaining an
unbounded set of user-selected colors.
