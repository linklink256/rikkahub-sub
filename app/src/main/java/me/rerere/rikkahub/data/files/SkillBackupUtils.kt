package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Shared utilities for skill backup/restore operations.
 *
 * Extracted from S3Sync and WebDavSync, which previously had identical
 * [restoreSkillEntry] implementations.
 */
object SkillBackupUtils {
    private const val TAG = "SkillBackupUtils"

    /**
     * Restores a single skill file entry from a backup zip.
     *
     * Parses the zip entry name to extract the skill name and relative path,
     * resolves them safely via [SkillPaths] (with path-traversal protection),
     * creates parent directories, and copies the zip content to the target file.
     *
     * @param context used to locate the skills directory
     * @param zipIn the zip input stream, positioned at the entry to read
     * @param entryName the zip entry name (e.g. `skills/my-skill/tools/echo.sh`)
     * @throws Exception if the entry name is invalid or the file cannot be written
     */
    fun restoreSkillEntry(context: Context, zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreSkillEntry: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreSkillEntry: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreSkillEntry: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }
}
