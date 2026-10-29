package app.orchard.tv.lite

// The child mode is not in Lite: it needs the quiz bank, the guard service and the parent
// password, and Lite's Home is a plain 4.x one. But LayoutStore is compiled here as the full app
// keeps it (see lite/build.gradle.kts), and it names the quiz tile's key: the tile opens the grid
// the first time and sorts first. No app in Lite carries that key, so both uses do nothing —
// which is the same as the full app on a TV where the child mode was never turned on.
object KidsMode {
    const val QUIZ_KEY = "kids:quiz"
}
