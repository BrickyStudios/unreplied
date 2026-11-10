# TODOS

Deferred from the feature review on 2026-11-10 (`/plan-ceo-review`). The accepted work (self-test, diagnostics, snooze reminder, reply detection in history, earlier reminders for favorites, weekly recap, IzzyOnDroid) is tracked in the releases, not here.

## Don't store message previews

- **What:** A switch that keeps only names and times, no message text.
- **Why:** Some people don't want any message content on disk, even locally.
- **Pros:** A stronger privacy story, about 10 lines of code (`Radar.PREVIEW` becomes a setting).
- **Cons:** The waiting list and the sprint lose the context of what someone asked.
- **Context:** `Radar.PREVIEW = 80` in `Radar.kt`. The sprint shows the preview as the message card.
- **Effort estimate:** human S / CC S
- **Priority:** P3
- **Depends on / blocked by:** nothing

## Quiet days for the daily reminder

- **What:** Choose days (e.g. weekend) without the daily reminder.
- **Why:** A reminder on a day off can feel like pressure.
- **Pros:** Small, fits the "one reminder a day" promise.
- **Cons:** One more setting.
- **Context:** `Summary.schedule` sets one alarm a day; the receiver would skip the chosen weekdays.
- **Effort estimate:** human S / CC S
- **Priority:** P3
- **Depends on / blocked by:** real usage feedback first

## Export and import settings

- **What:** Save quick replies, favorites, ignored chats and the hour to a file and load them on a new phone.
- **Why:** The app has no backup on purpose (`allowBackup="false"`).
- **Pros:** Moving phones keeps the setup.
- **Cons:** A file with names leaves app storage.
- **Context:** Settings live in SharedPreferences (`Prefs`), contacts in `radar.json`.
- **Effort estimate:** human S / CC S
- **Priority:** P3
- **Depends on / blocked by:** nothing

## Light theme

- **What:** A light colour scheme that follows the system setting.
- **Why:** Some people read better on light backgrounds.
- **Pros:** Accessibility, matches the phone.
- **Cons:** Every colour in `Ui.kt` needs a second value; screenshots twice.
- **Context:** Colours are constants in `Ui.kt` (Ink, CardBg, Coral …).
- **Effort estimate:** human M / CC S
- **Priority:** P3
- **Depends on / blocked by:** someone asking for it

## Email apps

- **What:** Track unanswered mail from K-9 / Thunderbird notifications.
- **Why:** The same "read it, never answered" problem exists for email.
- **Pros:** Reuses the whole pipeline.
- **Cons:** With email, opening a notification is not reading; needs its own premise check.
- **Context:** `MESSENGERS` in `Messengers.kt`; email notifications rarely use MessagingStyle.
- **Effort estimate:** human M / CC S
- **Priority:** P3
- **Depends on / blocked by:** a real-device test of how mail apps remove notifications
