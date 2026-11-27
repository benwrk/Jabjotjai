# In CI, "plugin was not found" can be a failed download, not a missing plugin

The download failure itself is unexplained. Why it then kept coming back is explained: a failed
run's incomplete Gradle cache was kept and restored (see "Why reruns kept failing").

## What happened

On 2026-11-27 the CI run for tag `v0.2.0` (run 46004980566, attempt 1) failed in 27 seconds,
while `settings.gradle.kts` was being evaluated. Later the same day it happened three times in a
row: master run 46035021402 at 16:36:21Z and its rerun at 16:38:05Z, then the `v0.5.0` tag run
46036187683. A master run between them, 46035565533 at 16:40:18Z, passed. Each failure was:

```
Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0'] was not found
in any of the following sources: ... Plugin Repositories (could not resolve plugin artifact ...)
Searched in the following repositories: Google, MavenRepo, Gradle Central Plugin Repository
```

## Ruled out

- **The code.** The master run for the same commit (46004973434) started nine seconds earlier and
  passed.
- **A plugin missing from the repositories.** Both the marker POM and the plugin's POM returned
  200 from `plugins.gradle.org` a few minutes later.
- **A recorded outage.** Gradle's status page listed no incident for that day
  (`status.gradle.com/api/v2/incidents.json`, read 2026-11-27).

## What the evidence shows

- **Gradle prints the same message for a network failure as for a missing plugin.** The message
  stays the same with `--stacktrace` and with `--info`. Only `--debug` shows the real error
  (`Could not GET … Connection refused`), along with retries after 1 s and 2 s. This was
  reproduced locally (Gradle 9.9.2) by sending all traffic to a dead proxy from an empty Gradle
  user home. A control run without the proxy got past plugin resolution.
- **On a repository error Gradle stops at that repository.** In the reproduction it never
  contacted `plugins.gradle.org`; it failed on Maven Central, which `pluginManagement` lists
  first and which returns 404 for this plugin. So the failing repository in CI could have been
  Maven Central or the Plugin Portal.
- **Both runs started with an empty Gradle cache.** setup-gradle's `basic` provider keys its
  cache on a hash of the build files and has no fallback keys, so the version bump in
  `app/build.gradle.kts` made both runs download everything at once. The rerun (attempt 2) got
  an exact hit on the cache the master run had saved, and needed no plugin download at all. Its
  success says nothing about whether the repositories had recovered.

## Why reruns kept failing

- **setup-gradle saves its cache even when the build fails.** Run 46035021402's first attempt
  started with no cache entry for its build files, failed on the plugin, and saved key
  `setup-java-Linux-x64-gradle-1e8d8f15…`. That entry was 129 MiB, where a complete one was
  about 596 MiB: little more than the Gradle distribution.
- **An entry is never replaced.** setup-gradle's docs say "once a cache entry is written for a
  key, it cannot be overwritten" (v6.4.1). The key is a hash of the build files, so every later
  run with the same build files restored the incomplete entry, and its own save was refused
  with "already exists". The rerun, the passing master run and the tag run all restored it.
- So every one of those runs downloaded everything again, and the flaky download had another
  chance to fail each time. A complete cache would have needed almost no network.

## Leading explanation, unconfirmed

Maven Central rate limiting. Sonatype says a 429 "means the aggregate traffic Maven Central sees
from your organization, network, provider, platform, toolchain, or shared egress path has crossed
a consumption threshold". It adds that "Rate limit blocks are temporary", and names "Hosted CI
systems" among the shared egress paths (central.sonatype.org/faq/429-error and
central.sonatype.org/faq/429-shared-egress, both read 2026-11-27). Whether GitHub's runners were
blocked that day was not checked, and nothing recorded the status code, so this remains a lead.

## If it happens again

- Read the failed run's "Record what the repositories answer" step. Since commit `c0b7338`, CI
  prints each repository's HTTP status to the runner and the failing request from a `--debug`
  attempt whenever the build step fails. A 429 there confirms rate limiting.
- Delete the cache entry the failed run saved (`gh cache list`, then `gh cache delete <id>`),
  or every rerun restores it. A cache entry of well under 500 MiB is the sign.
- Then re-run. A version tag goes up only after master's run for that commit has passed, so the
  tag run starts from a complete cache.
- Keep a `--debug` log out of uploaded artifacts. The job log showed the signing passwords as
  `***`, but nothing checked that an artifact gets the same treatment.
