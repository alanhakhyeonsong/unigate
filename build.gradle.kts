plugins {
  id("ktlint")
}

allprojects {
  apply {
    plugin("common")
    plugin("ktlint")
  }
}
