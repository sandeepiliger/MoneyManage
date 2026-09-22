# The assistant

## The abstraction

```kotlin
interface FinancialAiService {
    suspend fun ask(question: String, context: AiContext): AiAnswer
    fun suggestedQuestions(context: AiContext): List<String>
}
```

One interface, in `:core`, with no network dependency. `LocalFinancialAiService` implements it
entirely on-device and is what every plan gets. A cloud implementation is optional and additional.

## `LocalFinancialAiService`

Rule-based intent classification over the user's own data. It handles:

- "How much did I spend on food last month?"
- "What's my biggest expense this month?"
- "Can I afford ₹15,000 right now?"
- "How much am I spending on subscriptions?"
- "Am I saving more than last month?"
- "Where did my money go?"

No model, no network, no latency, and it works on a train with no signal. For the questions people
actually ask a finance app, a rule-based engine over structured data beats a language model that
has to be handed the data anyway — it is faster, free, private, and it cannot make a number up.

## Every answer carries its evidence

```kotlin
data class Answered(
    val summary: String,
    val evidence: List<Evidence>,
    val relatedTransactionIds: List<String>,
    val source: AnswerSource,
)
```

`evidence` is not optional, and the UI always renders it beside the summary.
`relatedTransactionIds` lets the user tap through to the rows the figure came from.

This is the rule that matters most here. **An assistant that states a number about someone's money
without showing where it came from is asking to be trusted on a subject where trust should be
earned per-answer.** If the figure is wrong, the user should be able to see that it is wrong.

`AnswerSource` distinguishes a local answer from a cloud one, and the UI shows which.

## Failure is a value, never an exception

`AiAnswer` is sealed: `Answered`, `NoData` (understood, but the data cannot answer it),
`NotUnderstood` (with suggested phrasings that would work), `Unavailable` (provider unreachable).

A question the assistant cannot handle produces a useful message, never a crash and never silence.

---

## Adding a cloud provider

### The consent gate

```kotlin
data class AiConsentState(
    val cloudProcessingEnabled: Boolean = false,  // user turned it on
    val hasEntitlement: Boolean = false,          // AI Pro
    val isConfigured: Boolean = false,            // this build has an endpoint
) {
    val canUseCloud get() = cloudProcessingEnabled && hasEntitlement && isConfigured
}
```

**All three must hold.** The first two default to off. `blockedReason()` gives the UI something to
say rather than silently falling back — a user who paid for AI Pro and does not know cloud
processing is off in Privacy settings should be told which switch to flip.

### What may be sent

Only what the question needs: the question itself, and the figures required to answer it — a
category total, a period comparison, an available balance. **Not the ledger**, not the account
list, not merchant history.

`AiContext` is passed to `ask()` explicitly rather than being fetched by the service, precisely so
that a cloud implementation must decide, visibly and reviewably, what it puts in a request body.

### `CloudAiConfig` enforces HTTPS at construction

```kotlin
require(endpoint.startsWith("https://"))   // financial data never travels in the clear
```

and `toString()` redacts the key, so a config can appear in a diagnostic without leaking it. A
build whose endpoint is not HTTPS treats cloud AI as unconfigured rather than crashing.

### Configuration

| Key | Meaning |
| --- | --- |
| `CLOUD_AI_ENDPOINT` | The full URL the app POSTs an OpenAI-style chat-completions request to. Required. |
| `CLOUD_AI_MODEL` | Sent as `model`. Leave empty for an Azure OpenAI deployment URL, which names the model already. |
| `CLOUD_AI_API_KEY` | Optional. When set it is sent as both `api-key` (Azure) and `Authorization: Bearer` (OpenAI). |

Examples of `CLOUD_AI_ENDPOINT`:

- Your own backend (recommended): `https://api.your-domain.example/khaata/chat`
- Azure OpenAI deployment: `https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=2024-10-21`
- Azure AI Foundry models endpoint: `https://<resource>.services.ai.azure.com/models/chat/completions?api-version=2024-05-01-preview` (set `CLOUD_AI_MODEL` to the deployment name)

### The key should not live in the app

`CLOUD_AI_ENDPOINT` is expected to point at **a backend you run**. That backend holds the provider
key, authorises requests itself (Play Integrity, a per-install token, rate limits), and forwards
the body unchanged to Azure or OpenAI.

An API key shipped in an APK is extractable by anyone who downloads the app, using `unzip` and
`strings`. It would bill to your account, and there is no obfuscation that fixes this — only not
shipping it does. `CLOUD_AI_API_KEY` exists for private test builds pointed straight at a provider;
leave it empty in anything you publish.

### How it works

`CloudFinancialAiService` (in `:core`, with tests) wraps the on-device engine:

1. The on-device engine answers first. Its figures are always what is shown under the answer.
2. If consent, entitlement and configuration all hold — re-checked on every question — the app
   sends the question plus `CloudAiPrompt.summarise`: six months of spending and income totals,
   this and last month by top-level category, budget progress, subscription names, and the
   on-device answer. Never transaction rows, merchants or account names.
3. The model's reply is shown as the answer, marked "Phrased using cloud AI".
4. Any failure — offline, a non-2xx status, an empty or malformed reply — shows the on-device
   answer instead.

The HTTP call is `HttpCloudAiTransport` in `:app`, over the platform's HttpURLConnection.

---

## What the assistant will not do

- **It never executes a financial action.** Natural-language entry parses a sentence into a *draft*
  transaction and the user confirms it. The assistant answers questions; it does not move money or
  write to the ledger.
- **It gives no regulated financial advice.** It reports what the user's own data says. It does not
  recommend investments, tell anyone to take or refinance a loan, or forecast returns. The loan
  screens state facts about amortisation; they do not advise.
- **It never invents a figure.** Every number in an answer comes from a calculation over the user's
  transactions, with the evidence attached.
