package me.rerere.rikkahub.data.files

/**
 * Shared constants for skill-related path resolution and script detection.
 *
 * Used by [resolveCommandPath] (SkillToolDeclaration.kt) and
 * [checkSkillScriptExecution] (WorkspaceTools.kt) to keep the list of
 * recognised script extensions in a single place.
 */
object SkillConstants {
    /** Known script file extensions used for path extraction and execution blocking. */
    val SCRIPT_EXTENSIONS: Set<String> = setOf(".sh", ".js", ".py", ".ts", ".rb")

    /**
     * Interpreters that can execute script files.
     *
     * Used by [checkSkillScriptExecution] to detect `bash /skills/foo/x.sh`
     * style commands. The base name (after last `/`) is compared.
     */
    val SCRIPT_INTERPRETERS: Set<String> =
        setOf("bash", "sh", "zsh", "python", "python3", "node", "deno", "ruby", "ts-node")
}
