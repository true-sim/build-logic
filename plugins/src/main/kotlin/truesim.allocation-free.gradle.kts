// Strict mode: production code must not allocate on the heap once the JIT has compiled it.
// `check` fails on allocations the JIT practically never removes (string building, autoboxing,
// capturing lambdas, Enum.values(), known allocating APIs). Tests verify the rest with
// com.truesim.testing.AllocationAssertions, which is added to the test classpath.
import com.truesim.build.strict.AllocationCheck
import com.truesim.build.strict.AllocationFreeExtension
import com.truesim.build.strict.BuildLogic

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

tasks.named("check") {
    dependsOn(checkAllocations)
}
