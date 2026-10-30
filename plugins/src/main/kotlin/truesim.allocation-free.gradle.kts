// Strict mode: production code must not allocate on the heap once the JIT has compiled it.
// `check` fails on allocations the JIT practically never removes (string building, autoboxing,
// capturing lambdas, Enum.values(), known allocating APIs). Tests verify the rest with
// com.truesim.testing.AllocationAssertions, which is added to the test classpath.
// `check` also enforces the frame graph's purity contract (PurityCheck): builds and pass bodies store into no fields
// and no arrays they did not make.
import com.truesim.build.strict.AllocationCheck
import com.truesim.build.strict.AllocationFreeExtension
import com.truesim.build.strict.BuildLogic
import com.truesim.build.strict.PurityCheck

plugins {
    id("truesim.java-conventions")
}

val allocationFree = extensions.create<AllocationFreeExtension>("allocationFree").apply {
    forbiddenApis.convention(
        listOf(
            "java.lang.String#format",
            "java.lang.String#formatted",
            "java.lang.String#valueOf",
            "java.lang.String#concat",
            "java.lang.String#join",
            "java.lang.String#split",
            "java.lang.String#repeat",
            "java.util.stream.*",
            "java.util.Collection#stream",
            "java.util.Arrays#stream",
        )
    )
    allowedApis.convention(emptyList())
    passBodies.convention(listOf("com.truesim.engine.graph.PassBody"))
    graphBuilders.convention(listOf("com.truesim.engine.graph.GraphBuilder"))
}

dependencies {
    "testImplementation"(BuildLogic.library("testing"))
}

val checkAllocations = tasks.register<AllocationCheck>("checkAllocations") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that production code avoids allocations the JIT cannot remove."
    classes.from(sourceSets.main.map { it.output.classesDirs })
    forbiddenApis = allocationFree.forbiddenApis
    allowedApis = allocationFree.allowedApis
    report = layout.buildDirectory.file("reports/truesim/allocations.txt")
}

val checkPurity = tasks.register<PurityCheck>("checkPurity") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that frame graph builds and pass bodies keep the purity contract."
    classes.from(sourceSets.main.map { it.output.classesDirs })
    bodyTypes = allocationFree.passBodies
    builderTypes = allocationFree.graphBuilders
    report = layout.buildDirectory.file("reports/truesim/purity.txt")
}

tasks.named("check") {
    dependsOn(checkAllocations)
    dependsOn(checkPurity)
}

// Compile synchronously in tests: AllocationAssertions then measures what the optimizing compiler produces, instead of
// racing it. With background compilation a method can get its optimized version before its profile is mature and keep
// allocations the JIT would otherwise remove.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-BackgroundCompilation")
}
