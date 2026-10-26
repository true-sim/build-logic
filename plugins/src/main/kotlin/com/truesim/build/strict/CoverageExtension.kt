package com.truesim.build.strict

import org.gradle.api.provider.Property

/** Configures the test coverage rule of `truesim.java-conventions`. */
abstract class CoverageExtension {
    /** Minimum line and branch coverage, from 0.0 to 1.0. Defaults to 0.8. */
    abstract val minimum: Property<Double>
}
