# `question`

Asks the user one question and waits for an explicit answer. Suggested answers
are optional.

Defined in [`LocalTools.question()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
pending questions are held in
[`QuestionBroker`](../../src/main/java/com/quaxt/claudia/agent/QuestionBroker.java).

## Parameters

| Name | Type | Description |
| --- | --- | --- |
| `question` | string | A short question that settles a real ambiguity or asks for a preference. |
| `options` | array, optional | Suggested answers. Each has a `label` and an optional `description` that explains the tradeoff. The user can always type their own answer instead. |

## Result

A JSON object with a `status` field:

| `status` | Meaning | Other fields |
| --- | --- | --- |
| `answered` | The user replied. | `answer` |
| `declined` | The user pressed Escape, or the agent was cancelled. | `question` |
| `unavailable` | Nobody can answer, for example in `--print`/JSON mode or after RPC stdin reached EOF. | `question` |

The tool description tells the model that `declined` and `unavailable` are
**not** answers. The model should keep the question open, not ask again, and
include it in its response. It must also never take an answer as approval to
switch from Plan to Build mode.

## Behaviour

- The calling agent's thread blocks until the question is resolved or the turn
  is aborted. Other agents keep running.
- In the interactive shell, the question shows which agent is asking. You can
  pick a suggestion or type an answer, then press Enter; Escape declines. If a
  selector or another prompt is open, the question waits for it to close, and
  your chat draft is kept.
- Chat messages that were already queued are never used as answers.
- RPC mode sends `question_requested` and `question_resolved` events and
  accepts `answer_question` commands.
- The tool is available in both Build and Plan modes, for Main and for child
  agents.

## UI summary

`Asking: <question>`
