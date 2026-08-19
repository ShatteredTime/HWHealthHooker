package moe.evil.hwhh.shared

private val SPLIT_APK_HASHES = Regex("""~~([^/=]+)==/[^/]+-([^/=]+)==""")

fun hostBuildTag(sourceDir: String): String =
    SPLIT_APK_HASHES.find(sourceDir)?.destructured?.let { (outer, inner) -> "${outer}_$inner" }
        ?: sourceDir
