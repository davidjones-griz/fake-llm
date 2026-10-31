package com.davidjonesgriz.fakellm

/**
 * The line the CLI prints for each call: `fake-llm: call <seq> <vendor> model=<json string>
 * outcome=<outcome> step=<n, n.m or ->`, where `n.m` is step m of the parallel step n. Holds
 * nothing from the request but the model name.
 */
internal fun callLogLine(record: CallRecord): String {
    val step = record.step?.let { n -> record.innerStep?.let { "$n.$it" } ?: "$n" } ?: "-"
    return "fake-llm: call ${record.seq} ${record.vendor} model=${jsonString(record.model)} outcome=${record.outcome} step=$step"
}
