package org.example.compiler

/**
 * Generates collision-free helper names for refs, temporaries, and lambda parameters.
 */
internal class NameGenerator {
    private var refIndex: Int = 0
    private var tempIndex: Int = 0
    private var lambdaIndex: Int = 0

    fun nextRefName(base: String): String {
        val safe = sanitize(base)
        return "__ref_${safe}_${refIndex++}"
    }

    fun nextTempName(base: String): String {
        val safe = sanitize(base)
        return "__tmp_${safe}_${tempIndex++}"
    }

    fun nextLambdaName(base: String): String {
        val safe = sanitize(base)
        return "__arg_${safe}_${lambdaIndex++}"
    }

    /**
     * Normalizes arbitrary source identifiers into Java-safe fragments.
     */
    private fun sanitize(value: String): String = value.replace(SANITIZE_REGEX, "_")

    companion object {
        /**
         * Shared regex for replacing non Java-identifier characters in generated names.
         */
        private val SANITIZE_REGEX = Regex("[^A-Za-z0-9_]")
    }
}
