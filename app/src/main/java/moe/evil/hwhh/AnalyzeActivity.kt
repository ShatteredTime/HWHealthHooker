package moe.evil.hwhh

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.serialization.json.Json
import moe.evil.hwhh.analysis.HealthIdAnalysis
import moe.evil.hwhh.analyzer.AnalysisOutcome
import moe.evil.hwhh.shared.log.HLog
import java.io.File
import kotlin.concurrent.thread

private val log = HLog.of<AnalyzeActivity>()

class AnalyzeActivity : Activity() {
    private val json = Json

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(view) })

        val lines = StringBuilder()
        fun report(line: String) {
            log.info { line }
            lines.appendLine(line)
            runOnUiThread { view.text = lines }
        }

        thread {
            report("!!!DEBUG ONLY!!!")
            when (val outcome = HealthIdAnalysis.run(this, log = ::report)) {
                is AnalysisOutcome.Done -> runCatching {
                    val (result, digest) = outcome.value
                    val out = File(getExternalFilesDir(null), "id2metadata.json")
                    out.writeText(json.encodeToString(result))

                    report("By source ${result.names.groupingBy { it.source }.eachCount()}")
                    report(
                        "By fact ${
                            result.facts.groupingBy { it.substringBefore('(') }.eachCount()
                        }"
                    )
                    report("Names=${result.names.size} distinct=${digest.names.size}")
                    report(
                        "Unresolved=${result.unresolvedOptions} " +
                                "jadxErrors=${result.jadxErrors} in ${result.elapsedMs}ms"
                    )
                    report("Written ${out.path}")
                }.onFailure {
                    log.error(it) { "Write failed" }
                    report("WRITE FAILED ${it.javaClass.simpleName}: ${it.message}")
                }

                is AnalysisOutcome.Cancelled -> report("CANCELLED")

                is AnalysisOutcome.Failed -> {
                    log.error(outcome.cause) { "Analysis failed" }
                    report("FAILED ${outcome.cause.javaClass.simpleName}: ${outcome.cause.message}")
                }
            }
        }
    }
}
