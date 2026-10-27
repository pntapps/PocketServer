package com.pntapps.pocketserver.catalog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every password, token, secret and key an app shows on its card must be generated on the device
 * at install time (gen_password, the app's own generator...), never written into a recipe:
 * otherwise every PocketServer would share it and one leak would open them all.
 */
class RecipeSecretsTest {
    private val catalog = File("src/main/assets/catalog")
    private val secretKey = Regex("password|passwd|token|secret|api ?key", RegexOption.IGNORE_CASE)
    private val credential = Regex("""set_credential\s+("[^"]*"|\S+)\s+(.+)""")
    private val knownDefaults = Regex("""\b(admin123|changeme|password123|letmein|secret123|pocketserver123)\b""", RegexOption.IGNORE_CASE)

    private fun recipes() = catalog.walkTopDown().filter { it.isFile && (it.extension == "sh" || it.extension == "py") }.toList()

    @Test fun recipesExist() {
        assertTrue("recipes not found under ${catalog.absolutePath}", recipes().size > 20)
    }

    @Test fun shownSecretsAreGeneratedNotLiteral() {
        val bad = recipes().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val m = credential.find(line) ?: return@mapIndexedNotNull null
                val key = m.groupValues[1].trim('"')
                val value = m.groupValues[2].trim()
                // A secret's value must come from a variable or command, not be spelled out in the recipe.
                if (secretKey.containsMatchIn(key) && !value.trimStart('"').startsWith("$")) "${file.parentFile.name}/${file.name}:${i + 1}: $line" else null
            }
        }
        assertTrue("Secrets must be generated at install time:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun noWellKnownDefaultPasswords() {
        val bad = recipes().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (knownDefaults.containsMatchIn(line) && !line.trimStart().startsWith("#")) "${file.parentFile.name}/${file.name}:${i + 1}: $line" else null
            }
        }
        assertTrue("Default passwords found:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
