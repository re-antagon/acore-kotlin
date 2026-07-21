package org.antagon.acore.exception

class MissingDependencyException(
    val dependencyName: String,
    val featureName: String,
    message: String = "Required dependency '$dependencyName' is missing or uninitialized for feature '$featureName'."
) : RuntimeException(message)
