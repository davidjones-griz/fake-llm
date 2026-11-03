package com.davidjonesgriz.fakellm

/**
 * The line the CLI prints for each call: `fake-llm: call <seq> <vendor> model=<json string>
 * outcome=<outcome> step=<n, n.m or ->`, where `n.m` is step m of the parallel step n, then
 * ` use=<k>` for the k-th call of a step with `times`, then ` name=<json string>` for a step with a
 * `metadata.name`. Holds nothing from the request but the model name.
 */
internal fun callLogLine(record: CallRecord): String {
    val step = record.step?.let { n -> record.innerStep?.let { "$n.$it" } ?: "$n" } ?: "-"
    val use = record.useNumber?.let { " use=$it" }.orEmpty()
    val name = record.stepName?.let { " name=${jsonString(it)}" }.orEmpty()
    return "fake-llm: call ${record.seq} ${record.vendor} model=${jsonString(record.model)} outcome=${record.outcome} step=$step$use$name"
}
