# AI access, models, and Chat routing

The pinned OpenClaw Gateway owns Provider methods, credentials, model catalogs,
verification, and Session routing. ClawInOne presents those capabilities; it has
no second Provider catalog, secret store, Provider installer, or direct config
writer. The exact supported protocol is OpenClaw 2026.9.4 with no legacy fallback.

## Product model

| Decision | Meaning | Canonical owner |
| --- | --- | --- |
| AI access | Subscription sign-in or API key | `openclaw.setup.*` |
| Default model | Verified route for the default Agent/future Chats | `agents.update` plus `openclaw.setup.verify` |
| Chat model/thinking | Override for one materialized Session | `sessions.patch` plus Session readback |

Authentication alone is not readiness. A route becomes Ready only after a real
Gateway model verification. Changing the default never rewrites an existing
Chat's explicit model.

## Supported access

ClawInOne exposes only Gateway-advertised methods in two classes:

1. **Sign in** — compatible OAuth or device-code methods. ChatGPT uses Gateway
   device pairing; fixed-port browser OAuth is hidden on Android.
2. **Use an API key** — compatible manual-key methods.

Install, custom endpoint, local model, CLI reuse, setup token, account picker,
and Provider Plugin management are not product surfaces. Standard pinned-runtime
Provider Plugins, including DeepSeek, require no ClawInOne installation flow.

Provider IDs, labels, choices, model rows, and thinking metadata come from the
current Gateway. Android applies only access-class and host-compatibility policy.

## Product surfaces

```text
Onboarding -> Access -> Model -> Verify -> Ready
Settings   -> AI access / Default model
Chat       -> Model / Thinking for this Session
```

- Onboarding establishes one verified default route. API-key activation submits
  the key with the selected `modelRef`; interactive sign-in may verify a starter
  model before the user selects another authenticated model.
- Settings contains exactly **AI access** and **Default model**. It reuses setup
  owners but not onboarding presentation.
- Chat options use the Gateway catalog and canonical Session patch/readback.
  Thinking appears only with the selected model's advertised levels/default.
  Android never exposes or chooses an auth profile.

## Protocol and lifecycle

Interactive setup follows the ordered 9.4 wizard:

```text
openclaw.setup.auth.start / openclaw.setup.activate.start
  -> wizard.next (read current step)
  -> wizard.next (exact answer)
  -> terminal modelActivation
```

`externalUrl`, not prompt type, controls **Open browser**. Polling pauses while
Chrome is foreground and resumes the same operation when ClawInOne returns.

- API keys exist only in the active input/controller operation and Gateway
  request; never in saved state, logs, Supervisor, or Android storage.
- OAuth tokens, device codes, and auth profiles remain Gateway-owned. Android may
  persist only safe operation identity needed for reconciliation.
- A restored wizard resumes with answer-free `wizard.next`; it never replays a
  credential or answer.
- Default-model changes are accepted only after verification. Failure restores
  the previous verified model.
- Unknown mutation outcomes use setup/Session readback before retry.
- A Gateway-requested restart retains its owner and resumes only after a distinct
  healthy generation; unrelated failure falls back to the Runtime Gate.
- Cancel, expiry, background return, and connection loss clear secret input and
  expose one truthful owner action.

## Acceptance

Focused checks cover access policy, secret lifetime, ChatGPT foreground return/
cancel, exact API-key model activation, default-model verify/rollback, unknown
outcome readback, Session model mutation, and model-specific thinking metadata.

First-release device acceptance needs one subscription sign-in, one API-key
route, one default-model verification, one per-Chat model/thinking change, and
one related cancellation/recovery where Provider access permits. Record actual
results or `BLOCKED`/`NOT RUN` in [validation](../validation.md).

Deferred: Android-defined Provider catalogs, Provider installation/hot loading,
local/custom endpoints, per-Chat auth-profile selection, global thinking
defaults, billing/quota UI, and broad Provider/model matrices.
