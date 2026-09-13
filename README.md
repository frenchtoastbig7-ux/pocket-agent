# Pocket Agent

A private, on-device personal agent for a GrapheneOS Pixel, with Claude as the model. It does the things Hermes Agent does (memory about you, self-written skills, past-chat search, scheduled automations, sub-agents) as a native Android app. It can also read your messages, calendar and notifications, and operate other apps on screen.

The phone talks to exactly one server: `api.anthropic.com`. There is no relay, no messaging gateway, no analytics, and no dependency on Google Play Services.

## Build

Requirements on the Mac: JDK 21 (`brew install openjdk@21`) and the Android SDK (platform 37.2, build-tools 36). The path is set in `local.properties`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install on GrapheneOS

1. Copy the APK to the phone and open it. GrapheneOS will ask you to allow installs from that source, once.
   Or use `adb install app/build/outputs/apk/debug/app-debug.apk` with USB debugging turned on.
2. Open Pocket Agent. Go to **Settings** and paste your Anthropic API key. It is encrypted with a Keystore key, backed by StrongBox / Titan M2 when available.
3. Tap **Grant missing permissions**. Where GrapheneOS offers Contact Scopes or Storage Scopes, you can grant those instead of full access.
4. For screen control and notification reading:
   1. Open **App info › ⋮ › Allow restricted settings**. GrapheneOS blocks both of these for sideloaded apps until you do.
   2. Turn on the accessibility service.
   3. Turn on notification access.
5. For automations, allow **Alarms & reminders** so runs happen on time.
6. Optional: set Pocket Agent as the **default digital assistant**, so long-press power opens it listening.
7. Optional, for voice: install a speech recognizer and a text-to-speech engine, such as RHVoice or SherpaTTS. GrapheneOS ships neither. On-device recognition is used when one is available.

Keep the app's **Network** permission on. This is the GrapheneOS per-app toggle, and the app needs it to reach the API.

## What it can do

| Area | Tools | Approval |
|---|---|---|
| Personal data | search contacts, list/send SMS, list/create calendar events, call log, recent notifications | Reading runs without asking. Sending an SMS asks **every** time. Creating an event asks. |
| Files | list/read/write in `Android/data/dev.pocketagent/files/workspace` | Writes ask |
| Web | Anthropic web search and web fetch (these run on Anthropic's servers) | No prompt |
| Device | status, list apps, open app | Opening an app asks |
| Screen control | read screen, screenshot, tap, type, scroll, swipe, back/home | Every action asks. You can choose "allow for this task" per tool. |
| Memory | `memory` (MEMORY.md / USER.md), `skill_view`, `skill_manage`, `session_search` | Memory and skill writes run without asking in a live chat, and are refused in unattended runs |
| Automations | list, create, pause, resume, delete scheduled tasks | Changes ask |
| Sub-agents | `delegate_tasks`: up to 4 parallel read-only research agents | No prompt (read-only) |

When you're in another app, approval prompts appear as an overlay drawn by the accessibility service. Without that service, they arrive as a notification. Tools run one at a time and the loop waits on each approval, so the agent can never tap its own Allow button.

## Hermes parity

| Hermes Agent | Pocket Agent |
|---|---|
| MEMORY.md + USER.md, bounded, snapshotted into the prompt at session start | Same files, entry format and size limits. Stored in app-private storage. Visible and deletable on the Memory tab. |
| Self-written skills (agentskills.io) | `skills/<name>/SKILL.md` with frontmatter. The index goes in the prompt; each skill body is loaded when needed. |
| FTS5 session search | SQLite FTS4, which is what Android's built-in SQLite includes |
| Cron scheduler with delivery | AlarmManager with a small schedule syntax (`daily 07:30`, `weekdays 08:00`, `weekly mon 09:00`, `every 2h`, `once <ISO>`). Results come as a private notification. |
| Subagents | `delegate_tasks`, read-only, effort `low` |
| SOUL.md persona | "Standing instructions" in Settings |
| Telegram/Signal/Discord gateway | Deliberately left out: no inbound command channel |

## Privacy and security model

- **Anything read through a tool is untrusted.** That includes SMS, notifications, web pages, files and screen text. The system prompt tells Claude to treat it as information, and results that carry other people's text are labeled that way.
- **Unattended runs are read-only.** Automations and sub-agents only receive tools that can't change anything. They also can't write memory or skills, so text read while nobody is watching can't plant instructions for later.
- **Data at rest** is in app-private, credential-encrypted storage. GrapheneOS file-based encryption keeps it unreadable until first unlock. The app isn't direct-boot aware, so automations don't run before you unlock after a reboot. Backups are disabled.
- **Screenshots of the app are blocked by default** (FLAG_SECURE), which also hides chats from Recents.
- **Lock screen:** notifications show only that something happened, never the content.
- **Wipe all agent data** in Settings deletes the key, memory, skills, history, automations and workspace.
- **What leaves the phone:** your prompts, tool results, the memory snapshot and skill index, and screenshots when the screenshot tool runs. All of it goes to Anthropic's API only.

## Model and API

- Model `claude-opus-5` with adaptive thinking (summaries shown in the chat) and configurable effort.
- Streaming, with prompt caching. The system prompt and the tool list stay byte-stable through a chat so they keep hitting the cache.
- Server-side refusal fallbacks (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`) are on. If the model declines, the API continues on a fallback model in the same request. Remove `.fallbacksDefault()` in `AgentLoop.kt` if you don't want that.
- Uses the `anthropic-java` SDK (beta Messages namespace).

## Tests

```bash
./gradlew testDebugUnitTest
```

`AgentLoopLiveTest` makes real API calls, a few cents' worth. It covers:
- a tool round trip
- a denied WRITE tool
- history replay across turns
- that the request shape is accepted

It only runs when you opt in:

```bash
POCKET_AGENT_LIVE_TEST=1 ANTHROPIC_API_KEY=sk-ant-... ./gradlew testDebugUnitTest --tests '*AgentLoopLiveTest*'
```

## Known limits

- The debug APK is about 65 MB, because it's unminified and the SDK pulls in Jackson, kotlin-reflect and a JSON-schema generator. R8 could shrink it but needs keep rules for Jackson.
- Apps that set FLAG_SECURE return black screenshots. `read_screen` usually still works on them.
- A chat's model, effort, instructions, memory and skill index are fixed when the chat starts. Use **New chat** to pick up changes.
- Long screen-control sessions resend earlier screenshots on every request. Start a new chat for unrelated tasks.

## License

Copyright 2026 Sovereign Edge Inc.

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).
