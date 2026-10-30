# Tab-Agent ("Hello")

Android tablet agent. **Phase 1:** when Claude's usage limit is hit, Hello reads the reset time from the screen
(or you type it), sets an exact alarm for reset + 2 min, then opens the Claude app and sends your saved
"continue" message. No AI involved, so it never runs out of limit itself.

## One-time GitHub setup

1. Repo → Settings → Secrets and variables → Actions → New repository secret:
   - `KEYSTORE_B64` = contents given to you in chat
   - `KEYSTORE_PASSWORD` = password given to you in chat
   (Keeps the signing key fixed, so new APKs install over old ones.)
2. Push to `main`. Actions builds the APK and publishes it as the **latest** release (`Hello.apk`).
3. Vercel → Add New Project → this repo → set **Root Directory** to `web` → Deploy.

Direct APK link: `https://github.com/Alok-MPM/Tab-Agent/releases/latest/download/Hello.apk`
(The download page needs the repo to be public. If you make it private later, host the APK elsewhere.)

## On the tablet

1. Install `Hello.apk`.
2. Open Hello and tick all 4 setup items. If Accessibility is greyed out: App info → ⋮ → **Allow restricted settings**.
3. A purple **H** bubble appears. Tap it to type commands.

## Commands (bubble or app)

| Command | What it does |
|---|---|
| `scan` | reads the Claude limit screen, finds reset time, schedules the message |
| `limit 2:30 pm` / `limit in 3h 20m` | schedules for that time (+ offset) |
| `limit 2:30 pm msg: long text` | schedules a custom message |
| `now` | sends right now (good for testing) |
| `msg <text>` | change the default message |
| `offset 3` | send 3 min after reset instead of 2 |
| `list`, `cancel` | see or clear pending tasks |

## Notes

- Tasks are stored on disk and re-armed after reboot or update.
- If the tablet is locked at send time, Hello notifies you and retries for 10 minutes.
- Claude's app UI can change; if sending breaks, the log in the Hello app says which step failed.

## Roadmap

Phase 2 voice + wake word ("Hello"), Gemini for fuzzy commands (via a Vercel `/api/gemini` proxy so the key
never ships in the APK), launcher home screen, Chrome + YouTube split-screen study mode.
