import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates a self-contained HTML report from a merged Autobahn index.json.
 * The report shows per-platform compliance results in a responsive table
 * with no external dependencies (all CSS/JS inlined).
 */
abstract class GenerateAutobahnReport : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:Input
    abstract val reportVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: Property<File>

    @TaskAction
    fun generate() {
        val indexFile = File(inputDir.get().asFile, "index.json")
        if (!indexFile.exists()) {
            logger.warn("No index.json found, skipping HTML generation")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parseText(indexFile.readText()) as Map<String, Map<String, Map<String, Any>>>

        val agents = json.keys.sorted()
        val allCases = json.values.flatMap { it.keys }.toSortedSet()

        // Group cases by category (e.g., "1.1.1" → "1")
        val categories = allCases.groupBy { it.substringBefore('.') }
            .toSortedMap(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })

        // Count results per agent
        data class AgentStats(var pass: Int = 0, var fail: Int = 0, var nonStrict: Int = 0, var info: Int = 0, var missing: Int = 0)

        val stats = agents.associateWith { AgentStats() }
        allCases.forEach { caseId ->
            agents.forEach { agent ->
                val result = json[agent]?.get(caseId)
                val behavior = result?.get("behavior")?.toString() ?: "MISSING"
                val s = stats[agent]!!
                when (behavior) {
                    "OK" -> s.pass++
                    "NON-STRICT" -> s.nonStrict++
                    "INFORMATIONAL" -> s.info++
                    "MISSING" -> s.missing++
                    else -> s.fail++
                }
            }
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val version = reportVersion.get()

        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>Autobahn Report — $version</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")

            // Header
            appendLine("<div class=\"header\">")
            appendLine("<h1>Autobahn TestSuite — RFC 6455 Compliance</h1>")
            appendLine("<p class=\"meta\">Version: <strong>$version</strong> | Generated: $timestamp | Platforms: ${agents.size}</p>")
            appendLine("</div>")

            // Platform badges
            appendLine("<div class=\"badges\">")
            agents.forEach { agent ->
                val s = stats[agent]!!
                val cssClass = if (s.fail == 0) "badge-pass" else "badge-fail"
                val total = s.pass + s.fail + s.nonStrict + s.info
                appendLine("<span class=\"badge $cssClass\">$agent: ${s.pass + s.nonStrict + s.info}/$total</span>")
            }
            appendLine("</div>")

            // Summary table
            appendLine("<h2>Summary by Category</h2>")
            appendLine("<table>")
            appendLine("<thead><tr><th>Category</th>")
            agents.forEach { appendLine("<th>$it</th>") }
            appendLine("</tr></thead>")
            appendLine("<tbody>")

            categories.forEach { (cat, cases) ->
                appendLine("<tr class=\"category-row\" onclick=\"toggleCategory('cat-$cat')\">")
                appendLine("<td><span class=\"toggle\">▶</span> $cat (${cases.size} cases)</td>")
                agents.forEach { agent ->
                    var pass = 0
                    var total = 0
                    cases.forEach { caseId ->
                        val behavior = json[agent]?.get(caseId)?.get("behavior")?.toString()
                        if (behavior != null && behavior != "MISSING") {
                            total++
                            if (behavior == "OK" || behavior == "NON-STRICT" || behavior == "INFORMATIONAL") pass++
                        }
                    }
                    val cssClass = when {
                        total == 0 -> "cell-na"
                        pass == total -> "cell-pass"
                        else -> "cell-fail"
                    }
                    val text = if (total == 0) "—" else "$pass/$total"
                    appendLine("<td class=\"$cssClass\">$text</td>")
                }
                appendLine("</tr>")

                // Per-case detail rows (hidden by default)
                cases.forEach { caseId ->
                    appendLine("<tr class=\"detail-row cat-$cat\">")
                    appendLine("<td class=\"case-id\">$caseId</td>")
                    agents.forEach { agent ->
                        val result = json[agent]?.get(caseId)
                        val behavior = result?.get("behavior")?.toString() ?: "MISSING"
                        val duration = result?.get("duration")?.toString()?.toIntOrNull() ?: 0
                        val cssClass = when (behavior) {
                            "OK" -> "cell-pass"
                            "NON-STRICT" -> "cell-nonstrict"
                            "INFORMATIONAL" -> "cell-info"
                            "MISSING" -> "cell-na"
                            else -> "cell-fail"
                        }
                        val label = when (behavior) {
                            "OK" -> "Pass"
                            "NON-STRICT" -> "Non-Strict"
                            "INFORMATIONAL" -> "Info"
                            "MISSING" -> "—"
                            else -> behavior
                        }
                        val durationText = if (duration > 0) " (${duration}ms)" else ""
                        appendLine("<td class=\"$cssClass\">$label$durationText</td>")
                    }
                    appendLine("</tr>")
                }
            }

            appendLine("</tbody>")
            appendLine("</table>")

            // JavaScript for expand/collapse
            appendLine("<script>")
            appendLine(JS)
            appendLine("</script>")

            appendLine("</body>")
            appendLine("</html>")
        }

        val outFile = outputFile.get()
        outFile.parentFile.mkdirs()
        outFile.writeText(html)
        logger.lifecycle("Generated HTML report: ${outFile.absolutePath}")
    }

    companion object {
        private val CSS = """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #f5f5f5; color: #333; padding: 20px;
            }
            @media (prefers-color-scheme: dark) {
                body { background: #1a1a2e; color: #e0e0e0; }
                table { background: #16213e; }
                th { background: #0f3460 !important; color: #e0e0e0; }
                .category-row:hover { background: #1a1a4e !important; }
                .header { background: #0f3460; }
                .detail-row td { background: #1a1a3e; }
            }
            .header {
                background: #2c3e50; color: white; padding: 20px 30px;
                border-radius: 8px; margin-bottom: 20px;
            }
            .header h1 { font-size: 1.5em; margin-bottom: 5px; }
            .meta { opacity: 0.8; font-size: 0.9em; }
            .badges { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 20px; }
            .badge {
                padding: 8px 16px; border-radius: 20px; font-weight: 600;
                font-size: 0.9em;
            }
            .badge-pass { background: #27ae60; color: white; }
            .badge-fail { background: #e74c3c; color: white; }
            h2 { margin-bottom: 10px; }
            table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
            th { background: #34495e; color: white; padding: 12px; text-align: center; font-size: 0.85em; }
            th:first-child { text-align: left; }
            td { padding: 10px 12px; border-bottom: 1px solid #eee; text-align: center; font-size: 0.85em; }
            td:first-child { text-align: left; }
            .category-row { cursor: pointer; font-weight: 600; }
            .category-row:hover { background: #f0f0f0; }
            .toggle { display: inline-block; width: 1em; transition: transform 0.2s; }
            .toggle.open { transform: rotate(90deg); }
            .detail-row { display: none; }
            .detail-row.visible { display: table-row; }
            .case-id { padding-left: 30px; font-family: monospace; font-size: 0.85em; }
            .cell-pass { color: #27ae60; font-weight: 600; }
            .cell-fail { color: #e74c3c; font-weight: 600; }
            .cell-nonstrict { color: #f39c12; font-weight: 600; }
            .cell-info { color: #3498db; }
            .cell-na { color: #95a5a6; }
        """.trimIndent()

        private val JS = """
            function toggleCategory(className) {
                const rows = document.querySelectorAll('.' + className);
                const isVisible = rows.length > 0 && rows[0].classList.contains('visible');
                rows.forEach(r => r.classList.toggle('visible', !isVisible));
                // Find the toggle arrow in the category row (previous sibling approach)
                const categoryRow = rows[0]?.previousElementSibling || document.querySelector('[onclick*="' + className + '"]');
                if (categoryRow) {
                    const toggle = categoryRow.querySelector('.toggle');
                    if (toggle) toggle.classList.toggle('open', !isVisible);
                }
            }
        """.trimIndent()
    }
}
