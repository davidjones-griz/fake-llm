package com.davidjonesgriz.fakellm

/**
 * The line the CLI prints for each call: `fake-llm: call <seq> <vendor> model=<json string>
 * outcome=<outcome> step=<n or ->`. Holds nothing from the request but the model name.
 */
internal fun callLogLine(record: CallRecord): String =
    "fake-llm: call ${record.seq} ${record.vendor} model=${jsonString(record.model)} " +
        "outcome=${record.outcome} step=${record.step ?: "-"}"
