package com.truesim.build.strict

/**
 * Matches member references against API patterns:
 * `java.lang.Math` (whole class), `java.lang.Math#sin` (one member) or `java.util.stream.*` (package and subpackages).
 */
class ApiMatcher(forbidden: Collection<String>, allowed: Collection<String>) {
    private val forbidden = forbidden.map(::Pattern)
    private val allowed = allowed.map(::Pattern)

    fun isForbidden(owner: String, member: String) =
        forbidden.any { it.matches(owner, member) } && allowed.none { it.matches(owner, member) }

    private class Pattern(spec: String) {
        private val type: String
        private val member: String?
        private val isPackage: Boolean

        init {
            val className = spec.substringBefore('#')
            member = spec.substringAfter('#', "").ifEmpty { null }
            isPackage = className.endsWith(".*")
            type = className.removeSuffix(".*").replace('.', '/')
        }

        fun matches(owner: String, name: String) =
            (if (isPackage) owner.startsWith("$type/") else owner == type) && (member == null || member == name)
    }
}
