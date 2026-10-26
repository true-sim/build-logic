package com.truesim.build.strict

import org.gradle.api.provider.ListProperty

/** Configures the platform-independence check. Patterns: `java.lang.System#nanoTime` or `java.util.concurrent.*`. */
abstract class DeterministicExtension {
    /** Additional APIs to forbid. java.lang.Math and java.lang.StrictMath are always forbidden. */
    abstract val forbiddenApis: ListProperty<String>
}

/** Configures `truesim.allocation-free`. Patterns: `java.lang.String#format` or `java.util.stream.*`. */
abstract class AllocationFreeExtension {
    /** APIs that always allocate, in addition to the built-in checks. */
    abstract val forbiddenApis: ListProperty<String>

    /** APIs allowed even if they match [forbiddenApis]. */
    abstract val allowedApis: ListProperty<String>
}
