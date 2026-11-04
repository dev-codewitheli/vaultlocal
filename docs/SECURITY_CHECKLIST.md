# VaultLocal — Security checklist (Phase 3)

Verified on 2026-11-05, Windows 11, JDK 21.0.1, VaultLocal 0.1.0.

Each item below has the method used and the observed result. Evidence comes from two places:

- **The automated suite:** `./gradlew test`, 109 tests, 0 failures.
- **`Phase3Smoke`:** a standalone evidence driver in `app/src/test/.../Phase3Smoke.java`. It runs
  the real use cases with the production adapters and **production Argon2id costs**, in its own
  JVM, so the OS can be asked about that exact process.

| # | Item | Result |
|---|---|---|
| 1 | No network access | ✅ Verified (static and runtime) |
| 2 | No plaintext secrets in the DB file | ✅ Verified (`sqlite3` CLI and raw-byte canary scan) |
| 3 | No secrets in logs | ✅ Verified (canary scans of real log files) |
| 4 | Memory zeroing for key material | ✅ In place, with stated JVM limits |
| 5 | Lockout delays persist across restart | ✅ Verified across two real processes |
| 6 | Recovery fully replaces the old master-key wrap | ✅ Verified (byte search of the DB file) |

---

## 1. No network access

**Static: dependencies.** Output of `./gradlew :app:dependencies --configuration runtimeClasspath`
(the shipped classpath):

```
org.openjfx:javafx-base / -graphics / -controls 21.0.6 (win)
org.xerial:sqlite-jdbc 3.47.1.0
org.bouncycastle:bcprov-jdk18on 1.79
net.java.dev.jna:jna-platform 5.15.0  ->  net.java.dev.jna:jna 5.15.0
```

None of these makes network calls in the code paths VaultLocal uses.
- **Bouncy Castle** is used only through `Argon2BytesGenerator` (its lightweight API). Its JCE
  provider is never registered, so the provider's CRL/LDAP code never loads.
- **JNA** makes local Win32 calls only (session lock, registry theme flag).
- **JavaFX `web` and `media` modules** are not dependencies at all.
- **Flyway** is not used (it ships with telemetry); a small in-house migration runner replaces it.

**Static: code.** Grep over every production source set:

```
grep -rnE "java\.net\.(http|Socket|ServerSocket|DatagramSocket|URLConnection|HttpURLConnection|
  InetAddress|InetSocketAddress|Proxy)|HttpClient|openConnection|openStream|new URL\(|URL\.of\(|
  javafx\.scene\.web|WebView|HostServices|showDocument|Desktop\.getDesktop\(\)\.(browse|mail)"
→ (no matches)

import java.net.* → only java.net.URI and java.net.URISyntaxException in EntryDetails.java
                    (syntax check of the optional Website URL field; the URL is never opened)
```

**Static: enforced in the build.** `ArchitectureTest` (ArchUnit, app module) fails the build if
any production class depends on any of the following:
- `java.net.http`, `Socket`, `ServerSocket`, `DatagramSocket`, `URLConnection`, `HttpURLConnection`,
  `InetAddress`, `InetSocketAddress` or `Proxy`
- `java.nio.channels.spi`
- calls to `URL.openConnection` or `URL.openStream`
- `javafx.scene.web` or `javafx.scene.media`

A second rule allows only `URI`, `URISyntaxException` and `URL` (for classpath resources) from
`java.net`. A guard test (`everyModuleIsActuallyChecked`) asserts that classes from all six
modules were imported. Before that guard was added, these rules were passing on an empty class
set; the guard now prevents that.

**Static: runtime image.** `app/build/jpackage/runtime/release`:

```
MODULES="java.base java.datatransfer java.xml java.prefs java.desktop java.logging
         java.transaction.xa java.sql jdk.unsupported"
```

The image excludes `java.net.http`, `jdk.httpserver` and `java.naming` (JNDI, LDAP, DNS). jdeps
listed `java.naming` only because of Bouncy Castle's unused CRL cache, so it was left out.
`java.base` itself always contains `java.net`, which is why the ArchUnit rules above exist.

**Runtime: evidence driver (production KDF, every flow).** The driver covered setup (both wraps),
3 entries, an edit with history, reveal, copy, historic reveal, backup export and import, the
security report, a wrong key, and a master-key change and change back. It ran with a JFR
recording configured for `jdk.SocketRead`/`jdk.SocketWrite` at a 0 ms threshold, while
PowerShell sampled `Get-NetTCPConnection` / `Get-NetUDPEndpoint -OwningProcess <pid>`:

```
PID 22104  samples=11  TCP-seen=0  UDP-seen=0
jfr summary flows.jfr (12 s):   jdk.SocketRead 0   jdk.SocketWrite 0
```

**Runtime: packaged GUI app.** `VaultLocal.exe` from the jpackage image. The launcher (PID 36576)
hands off to the app process (PID 7312); both were sampled every 0.5 s for 15 s:

```
Samples: 30   TCP seen: 0   UDP seen: 0
```

> Correction to an earlier claim: the first smoke test of the packaged app (Phase 2) sampled only
> the launcher's PID, not the app process it starts. The measurement above covers both.

**Limits of this evidence.** The GUI run covers startup and the idle Setup screen. The
UI-driven flows were network-checked through the evidence driver, which calls the same use
cases. Desktop UI automation of the packaged window was out of scope. Native libraries (SQLite,
JNA, JavaFX) unpack into `<data dir>/native`, not into a shared temp directory.

---

## 2. No plaintext secrets in the DB file

After the evidence-driver run (`vault.db`, 61,440 bytes), with
`sqlite3 3.50.6` (Android SDK platform-tools):

```
sqlite> SELECT DISTINCT typeof(platform), typeof(account_label), typeof(username),
               typeof(password), typeof(website_url), typeof(notes) FROM entry;
blob|blob|blob|blob|blob|blob
blob|blob|blob|blob|blob|null            ← notes left empty

sqlite> SELECT id, hex(platform), hex(account_label), hex(password) ... LIMIT 1;
b9dad12a-…|01D367F83B550B56B500B150DC…|0162C4933463086A376387B701…|01EFBC9CF376CD9D825D2808C1…
           ^ version 0x01, then 12-byte random nonce, ciphertext, 16-byte tag

sqlite> SELECT wrap_type, kdf_memory_kib, kdf_iterations, kdf_parallelism, length(wrapped_dek) FROM key_wrap;
MASTER   |131072|3|4|61      (61 = 1 version + 12 nonce + 32 DEK + 16 tag)
RECOVERY |262144|6|4|61

sqlite> SELECT slot, question_key, custom_text FROM security_question;
1|FIRST_PET|   2|FIRST_CONCERT|   3|FIRST_CAR|     ← questions only; answers are never stored
```

**Canary scan.** The run used 14 unique canary strings: master keys (old, new and a wrong one),
the three answers (raw and normalized), three passwords, a label, the username, the notes and
the URL. Every byte of every file the run wrote (`vault.db`, `export.vlbackup`,
`vaultlocal.0.log`) was searched for each canary in UTF-8, UTF-16LE and UTF-16BE:

```
Files scanned: 3; canaries: 14 x 3 encodings; hits: 0
sqlite3 .dump: 88 lines, canary hits (case-insensitive): 0
```

No `-journal`, `-wal` or `-shm` file remains next to `vault.db`
(`journal_mode=DELETE`, `secure_delete=ON`, `temp_store=MEMORY`; asserted in
`SqliteRepositoriesTest.migratesAndAppliesHardeningPragmas`). Automated equivalents:
`SqliteRepositoriesTest.noPlaintextCanaryAppearsInTheDatabaseFile` and
`VaultFlowsTest.backupRoundTrips…` (canaries absent from the backup file).

**What is plaintext by design** (DESIGN.md §3.3): ids, timestamps, the favorite flag, question
choices, custom question text, the master-key length (for the Hint), lockout counters, the
activity log (event type, time, opaque entry id), and settings.

---

## 3. No secrets in logs

- Logging is `java.util.logging` to `<data dir>/logs` only (1 MB × 3 files). There is no network
  handler.
- Errors are logged by class name, error code and stack frames (`UiErrors.describe`). The
  message of a third-party exception is never logged, because it could echo input.
  `VaultException` messages are fixed `ErrorCode` text.
- `Secret`, `SecretBytes`, `Kek`, `Unwrapped`, `KeyWrap`, `EntryRecord` and `SecretField` all
  override `toString()` (for example `Secret[***]`).

Evidence:

```
NoSecretsInLogsTest (production Logging config): setup, add entry, wrong unlock, plus a simulated
third-party exception whose message and cause contain the password and master key
→ log contains "simulated library failure", "java.lang.IllegalStateException", "WRONG_MASTER_KEY"
→ log contains none of the 5 canaries

Evidence-driver log (vaultlocal.0.log, real run): part of the 14-canary scan above → 0 hits
```

Example of what a logged failure looks like:
`SEVERE vaultlocal.ui: expected wrong key: …WrongSecretException[WRONG_MASTER_KEY] at …`.

**Crash output.** The launcher passes `-XX:-CreateCoredumpOnCrash` (no Windows minidump of
process memory) and `-XX:+SuppressFatalErrorMessage` (no `hs_err` file), plus
`-XX:+DisableAttachMechanism`, which blocks `jcmd`/`jmap` heap dumps by other same-user
processes. Verified in the packaged `app/VaultLocal.cfg`:

```
java-options=-Xmx768m
java-options=-XX:+DisableAttachMechanism
java-options=-XX:-CreateCoredumpOnCrash
java-options=-XX:+SuppressFatalErrorMessage
```

---

## 4. Memory zeroing for key material

**Mechanism.** Secrets live in `Secret` (`char[]`) and `SecretBytes` (`byte[]`), whose `close()`
zeroes the array. They are used in try-with-resources: 62 `try (` blocks and 23 `Arrays.fill`
calls in production code. UTF-8 conversion goes through `CharsetEncoder` into buffers that are
zeroed afterwards, so no `String` is involved.

| Secret | Created | Zeroed |
|---|---|---|
| Master key / answers being typed | `SecretField` `char[]` buffer (old buffers zeroed on growth) | `takeSecret()` / `clear()` zero the buffer; the field is cleared when the screen or dialog closes |
| Master key passed to a use case | `SecretField.takeSecret()` | try-with-resources in the UI worker task |
| Normalized answers | `AnswerNormalizer` (scratch array zeroed) | `RecoveryAnswers.close()` |
| KDF input (UTF-8 bytes) | `SecretCodec.utf8` | try-with-resources in `VaultService` |
| KEK | `Argon2idKdf.derive` | `Kek.close()`; on a failed unwrap it's closed before returning |
| DEK | `KeyHierarchy.newDek` / unwrap | `VaultSession.lock()`; also when replaced by `open()` |
| Recovery ticket (DEK + KEK) | `verifyAnswers` | `completeRecovery` (`finally`), cancel, or screen dispose |
| Entry password (reveal/copy) | `EntryCipher.openPassword` (decrypt buffer zeroed) | try-with-resources once it's shown or copied |
| Generated password / master key | `GeneratorService` | `MasterKeyForm.dispose()` / editor close |
| Backup payload | `ZeroingBuffer` (old buffers zeroed on growth) | zeroed after sealing / restoring |

**Tests that check the arrays themselves, not just references:**
- `SecretTest.closeZeroesTheOwnedArray`
- `secretBytesCloseZeroesTheOwnedArray`
- `ZeroingTest.lockingTheSessionZeroesTheDekArray`, `reopeningTheSessionZeroesThePreviousDek`,
  `recoveryAnswersZeroTheirNormalizedCopies`, `utf8EncodingLeavesNoCopyBehind`
- `OptionalFeaturesUiTest.secretFieldEditsWithoutExposingText`, which reads `SecretField`'s
  internal buffer after `takeSecret()` and finds it all `\0`

**Where a `String` is unavoidable** (grep `new String(… .chars())` in production code returns
exactly two sites):
1. `EntryRow.reveal`: a JavaFX `Text` node can render only a `String`. It is dropped after 15 s,
   or earlier on focus loss, minimize or lock.
2. `ClipboardService.copy`: the OS clipboard takes a `String`. It is cleared after 20 s if
   unchanged, and on lock and exit.

Two more display-only cases follow the same pattern: the generated master key, shown once, and
`SecretField`'s optional "Show" toggle. JavaFX also delivers each typed character as a
1-character `String`.

**JVM limits (stated, not solved):**
- The GC may copy arrays before they are zeroed.
- `SecretKeySpec` and the AES key schedule inside `Cipher` hold copies that cannot be wiped.
- The OS may page memory to the pagefile or hibernation file.

The mitigation for paging is full-disk encryption (USER_GUIDE.md).

---

## 5. Lockout delays persist across restart

Two separate processes, run by the evidence driver against the same `vault.db`:

```
== lockout-a (PID 36232)
   failure 1 -> lockout 1s
   failure 2 -> lockout 2s
   failure 3 -> lockout 4s
   failure 4 -> lockout 8s
   process exits now; MASTER lockout remaining=7987ms
== lockout-b (PID 12136, new JVM)
   new process; MASTER lockout remaining=6698ms
   correct key refused while locked out, remaining=6679ms
   after the delay: unlocked=true
sqlite> SELECT * FROM lockout_state;   →   MASTER|0||      (reset after the successful unlock)
```

Automated equivalents:
- `VaultFlowsTest.wrongMasterKeyGivesProgressiveDelayThatSurvivesRestart` (1, 2, 4, 8 s, then reopen)
- `settingTheClockBackDoesNotEndTheLockout`
- `wrongAnswersUseTheirOwnLockoutChannel`
- `PolicyTest.lockoutDelayDoublesAndCapsAtSixtySeconds`
- UI: `CriticalFlowsUiTest` (the input is disabled with a countdown)

The stated limit (DESIGN.md §5.2): someone who copies the file can attack it offline without
any delay. The delay stops UI guessing only; Argon2id is the offline defense.

---

## 6. Recovery fully replaces the old master-key wrap

Evidence-driver `recover` run (a new process) against the vault from item 2, with
DEK rotation (decision Q1):

```
BEFORE gen=1  MASTER salt=62452A9AA4E901472E59FBDCFEFA7AE8
   recovered with normalized answers ("  canarypetname ", "CANARYCONCERT", …)
   old master key rejected
   entry readable with new key after DEK rotation: true
AFTER  gen=2  MASTER salt=D91C300CB30ACABA3027383484C97982  length=31
MASTER salt changed: True; MASTER wrap changed: True
RECOVERY salt kept (resealed with held KEK): True; RECOVERY wrap changed: True
Old MASTER wrapped_dek bytes anywhere in vault.db: False
Old RECOVERY wrapped_dek bytes anywhere in vault.db: False
Old (generation-1) password ciphertext anywhere in vault.db: False
Side files next to vault.db: 0
```

Because the DEK was rotated, even a copy of the old wrap made **before** recovery (an old backup
of `vault.db`, or SSD remnants) unlocks only a key that no longer encrypts anything in the
current file. Automated equivalent:
`VaultFlowsTest.recoveryRotatesTheDekAndFullyReplacesTheOldMasterWrap`, which byte-searches the
file for the old wrap and the old ciphertext.

**Limit.** A `.vlbackup` exported before recovery is still protected by the old master key. That
is by design (USER_GUIDE.md, "Backups").

---

## Also verified

| Check | Evidence |
|---|---|
| Argon2id is correct | RFC 9106 §5.3 test vector (`Argon2idKdfTest`) |
| AES-256-GCM is correct; tampering fails closed | GCM spec test cases 13/14; flipping any byte fails; wrong AAD or key fails; 100,000 nonces all unique (`AesGcmAeadTest`) |
| Ciphertexts can't be moved between rows or fields | wrong type, vault or generation fails (`KeyHierarchyTest.wrapsAreBoundToTheirTypeVaultAndGeneration`) |
| Production KDF cost on this machine | master 0.35–0.47 s, recovery 1.4–2.7 s, setup with both wraps 2.5 s |
| Copied passwords stay out of Windows Clipboard History and cloud clipboard | `windowsClipboardSpike` on the real clipboard: all three exclusion formats present |
| Hexagonal boundaries | ArchUnit: domain → JDK only; application → no adapters or frameworks; adapters independent; persistence never sees `Entry` or `Secret` |
| Installer layout | the `.msi` installs per-user to `%LOCALAPPDATA%\VaultLocal App`, separate from the data dir `%LOCALAPPDATA%\VaultLocal`, with a pinned upgrade code (checked by decompiling with WiX `dark.exe`) |

## Residual risks (unchanged from DESIGN.md §5.2)

- Malware or a keylogger running as you while the vault is unlocked.
- Pagefile and hibernation copies of memory.
- Windows Error Reporting, if it has been configured system-wide to collect full dumps.
- Clipboard managers that ignore the exclusion formats.
- Offline guessing against a weak master key or guessable answers.
- Whole-file rollback or deletion.

How to reproduce:

```
./gradlew test
./gradlew :adapters:crypto:test -Pbenchmark
./gradlew :adapters:ui-javafx:windowsClipboardSpike
./gradlew :app:smokeClasspath   # then run Phase3Smoke <flows|lockout-a|lockout-b|recover> <dir>
./gradlew :app:msi -Pwix.dir=C:\path\to\wix314
```
