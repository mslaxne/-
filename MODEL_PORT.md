# Model port

This stage generates a reply and writes it to disk. It never switches the IME,
types into WeChat, or taps send.

## Implemented

- Bundled `personas/me.md` and six real voice examples from the handoff snapshot.
- Persona headings and HTML comments are removed before sending.
- Examples travel as real user/assistant turns, framed by system boundaries.
- Local date/time and quiet-gap notes are placed before conversation history.
- Twelve-turn per-conversation in-memory history.
- Lookalike/split-radical interpretation before the model sees a message.
- DeepSeek Chat Completions over HTTPS:
  - `deepseek-flash`
  - `thinking.type=disabled`
  - temperature 1.0
  - max_tokens 500
- Identity-suspicion guard:
  - detects direct, premise and behavioural probes
  - rewrites once if an AI/model/program word leaks
  - falls back to one of three fixed deflections on a second leak
- Prompt-injection guard:
  - detects instruction/prompt/role/debug/model-parameter probes
  - ignores the payload and requests one dismissive line
  - rewrites once if the reply is still substantive
  - falls back to a fixed dismissal
- Empty replies and network failures are errors and do not remain in history.
- Replies are clamped to 200 characters.
- The API key is entered in a password dialog and encrypted with AES-GCM in
  Android Keystore. It is not stored in source, assets, or logs.

## Output

Loop-generated replies go to:

```text
Android/data/com.local.wxbotprobe/files/replies/reply-*.json
```

Each file contains the reply text, model, latency and token usage, with
`"sent": false`.

## Verification

- 52 JVM tests pass.
- Tests cover probes, rewrite order, fixed fallbacks, history failure rollback,
  persona turn ordering, clock/gap notes, lookalike text, request payload and
  response parsing.
- Lint reports no issues.

## Live smoke test

The owner supplied a temporary key in chat. A direct API check returned `OK`
with HTTP 200. The key was then entered only through the app's password dialog.

The encrypted preference file contains ciphertext and an IV, and does not
contain the plaintext key.

`生成测试回复（不发）` produced:

```json
{
  "text": "在",
  "model": "deepseek-flash",
  "elapsedMs": 1013,
  "latencyMs": 951,
  "promptTokens": 1677,
  "completionTokens": 1,
  "sent": false
}
```

This confirms the full on-phone path: Keystore decrypt → persona/examples →
history/time assembly → DeepSeek request → guardrails → reply file. No WeChat
text or send tap occurred.
