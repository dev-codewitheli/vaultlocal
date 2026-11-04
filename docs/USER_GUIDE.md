# VaultLocal — User guide

VaultLocal keeps your passwords in an encrypted file on **this computer only**. It never
connects to the internet: there is no account, no cloud, no sync, and no update check. That
also means nobody can reset your master key for you, so read **Recovery** and **Backups** below.

---

## Install

1. Run `VaultLocal-0.1.0.msi`. It installs for your Windows user only, so no administrator
   rights are needed.
2. Choose the install folder, or keep the default `%LOCALAPPDATA%\VaultLocal App`, and decide
   whether you want a desktop shortcut.
3. Start **VaultLocal** from the Start menu.

**Where your data lives:** `%LOCALAPPDATA%\VaultLocal\vault.db`, kept separate from the
program files. Uninstalling VaultLocal removes the program but **leaves your vault file in
place**.

> **Recommended:** turn on BitLocker (Settings → Privacy & security → Device encryption). While
> the vault is unlocked, Windows can write parts of memory to the page file or hibernation file.
> Disk encryption protects those copies too.

---

## First-run setup

### Step 1: Create your master key

The master key unlocks everything. It is **never stored**. VaultLocal keeps only its length, so
the Hint button can tell you how many characters it has.

You have two choices:

- **Type my own.** Use at least 12 characters, and type it twice. The strength meter must reach
  at least *Fair*, so very predictable keys (like `password1234`) are refused even when they're
  long enough. A short sentence of unrelated words works well: `violet anchor brisk tundra 42`.
- **Generate one for me.** VaultLocal creates a random 24-character key (about 139 bits).
  - **It is shown only once.** Write it down or put it somewhere safe.
  - The spaces in the display are only for readability. Don't type them.
  - Copy puts it on the clipboard for 20 seconds.
  - You can't continue until you tick **I've saved it somewhere safe**.

### Step 2: Choose three security questions

These are the only way back in if you forget your master key.

- Pick three different questions from the list, or choose **Write my own…** to write your own.
- Type each answer twice. Each answer needs at least 3 characters.
- **How answers are compared:**
  - Spaces at the start and end are ignored.
  - Several spaces in a row count as one.
  - Upper/lower case doesn't matter.
  - Everything else, including punctuation and accents, must match exactly.
  - So `New  York` and `new york` match, but `St. Louis` and `St Louis` don't.

> ⚠️ **Security questions are weaker than your master key.** Anyone who can guess or look up
> all three answers can open your vault. Answers like your first pet's name are often on social
> media. Prefer answers only you would know, or treat them like passwords: you can answer
> *"What was your first car?"* with `purple-umbrella-1999`.

If you prefer, tick **Advanced: don't set up recovery (most secure)**. Then a forgotten
master key means your data is gone for good. You can turn recovery on later in Settings.

Click **Create vault**. Deriving the keys takes a few seconds; the delay is deliberate,
because it makes guessing very slow for an attacker.

---

## Everyday use

### Unlock

Enter your master key and press **Enter**. If you can't remember it, **Hint** shows its
length, for example *"Your master key has 24 characters."*. Nothing else about it is shown.

**Wrong key?** Each wrong attempt makes you wait longer before the next one: 1 s, 2 s, 4 s,
8 s … up to 60 s. The wait continues even if you close and reopen VaultLocal. It resets after
a successful unlock.

### The list

| Column | |
|---|---|
| Platform | Coloured badge: Facebook, TikTok, Gmail, Instagram, X, Bank, Other, or your own name |
| Account label | Your name for the account (e.g. *Personal*, *Work*). ★ = favorite. **Stale** = password not changed for 90+ days |
| Username | Login name or email |
| Password | Always `••••••••`, whatever the real length. A **Weak / Fair / Strong** chip shows its strength |
| Last updated | When the entry was last edited |
| Actions | Reveal · Copy · Edit · History · ☆ pin · Delete |

You can sort by clicking a column header. Favorites always stay on top. Search filters by
platform, label or username, and the dropdown filters by platform.

### Reveal and copy

Both ask for your master key **every time**:

- **Reveal** shows the password for **15 seconds**, then hides it again. It hides immediately
  if you minimize the window or switch to another app.
- **Copy** puts the password on the clipboard and **clears it after 20 seconds**, unless
  you've copied something else since. Copied passwords are marked so they don't appear in
  Windows Clipboard History (Win+V) and aren't synced to other devices.

Right-click a row to copy the **username** or **website URL**. These aren't secret, so they
don't ask for the master key. VaultLocal never opens websites itself.

### Add and edit

**+ Add entry** asks for:
- the platform (or **Custom…** with your own name)
- an account label, and a username or email
- the password
- optionally, a website URL and notes

**Password generator** (inside the dialog) lets you choose the length (8–128), the character
types, and whether to exclude look-alike characters (`0 O o 1 l I |`).

When editing, leave the password empty to keep the current one. Changing a password keeps
the old one in **History**, which holds the last 5. Each of those also needs the master key to
reveal.

**Delete** always asks you to confirm. It also deletes that entry's password history.

### Locking

The vault locks:
- when you click **Lock**
- after **5 minutes** without keyboard or mouse use (change this in Settings, 1–60 minutes)
- when you lock Windows (Win+L), or the computer goes to sleep

Locking forgets the encryption key, closes dialogs, and clears a copied password from the
clipboard.

### Security report

The security report lists entries whose passwords are **weak**, **reused** on more than one
account, or **stale**. It shows entry names only, never passwords.

---

## Recovery: forgot your master key

1. On the unlock screen, click **Forgot master key?**
2. Answer **all three** questions. Spacing and capitals don't matter (see the rules above).
   You won't be told which answer was wrong. Wrong answers have their own waiting time, which
   works like the master key's.
3. Choose a **new master key**, typed or generated.
4. VaultLocal re-encrypts everything with a brand-new internal key. **Your old master key stops
   working**, and the vault opens.

Your security questions stay the same afterwards. Change them in **Settings** if you like.

**Change your master key or questions without forgetting them:** go to Settings →
**Change master key…** or **Change security questions…**. Both ask for your current master key.

---

## Backups

The vault is a single file on this computer. If the disk fails or the laptop is lost, only a
backup can bring it back.

### Export

**Settings → Export encrypted backup…** → choose where to save it (a USB drive is a good choice)
→ enter your master key.

- The `.vlbackup` file is encrypted with **the master key you have now**, using the same
  strong method as the vault (Argon2id + AES-256-GCM). It contains every entry and its password
  history.
- Make a new backup after adding important entries, and after changing your master key.

### Import

**Settings → Import backup…** → choose the file → enter **the master key that was active when the
backup was made** → choose a mode:

- **Merge:** adds entries that aren't already in this vault (exact duplicates are skipped).
- **Replace:** deletes everything in the current vault first, then restores the backup. You'll
  be asked to confirm.

To restore on a new computer: install VaultLocal, set up a new vault (any master key), then
import the backup with its own master key.

> An old backup still opens with the master key it was made with, even after you change or
> recover your key. If you changed your key because someone may have learned it, delete old
> backups.

---

## Settings

- Auto-lock minutes.
- Days before a password counts as stale (default 90).
- Theme: follow Windows, Light or Dark.
- Change master key, change security questions, or turn recovery off.
- Export and import backups.
- The **activity log**: unlocks, reveals, copies and failed attempts, with times. Secrets are
  never logged.

---

## What VaultLocal does and doesn't protect against

**Protects you when:**
- your laptop is stolen, or someone copies the vault file (everything you typed is encrypted)
- someone looks over your shoulder (passwords are masked, every reveal needs the key, and they
  hide again automatically)
- someone sits at your unlocked computer (reveal and copy still need the key, and the vault
  auto-locks)

**Can't protect you from:**
- malware or a keylogger running on your computer while you use the vault
- someone who knows or guesses your master key, or all three answers
- losing the file without a backup

---

## Troubleshooting

| Problem | What to do |
|---|---|
| "VaultLocal is already running" | Only one window can open the vault. Switch to the existing window. |
| "Too many failed attempts. Try again in N seconds." | Wait for the countdown. It keeps counting after a restart. |
| Forgot master key, and recovery is off | The data can't be recovered. Restore a backup (you need that backup's master key) or start a new vault. |
| "The vault data could not be decrypted…" | The file is damaged or was modified. Restore from a backup. |
| Need the log | `%LOCALAPPDATA%\VaultLocal\logs\` (it contains no passwords). |
