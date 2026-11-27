# In CI, "plugin was not found" can be a failed download, not a missing plugin

A closed investigation, not a solved one: the cause was never confirmed.

## What happened

On 2026-11-27 the CI run for tag `v0.2.0` (run 46004980566, attempt 1) failed in 27 seconds,
while `settings.gradle.kts` was being evaluated:

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

## Leading explanation, unconfirmed

Maven Central rate limiting. Sonatype says a 429 "means the aggregate traffic Maven Central sees
from your organization, network, provider, platform, toolchain, or shared egress path has crossed
a consumption threshold". It adds that "Rate limit blocks are temporary", and names "Hosted CI
systems" among the shared egress paths (central.sonatype.org/faq/429-error and
central.sonatype.org/faq/429-shared-egress, both read 2026-11-27). Whether GitHub's runners were
blocked that day was not checked, and nothing recorded the status code, so this remains a lead.

## If it happens again

- Re-run the failed job from GitHub. If master's run for the same commit has finished, the
  rerun restores its cache.
- Before re-running, capture what the runner actually received. A `--debug` run of only the
  failing configuration (`./gradlew help --debug`, written to a file and searched for
  `Could not GET` and `Caused by`) names the repository and the error.
- Keep a `--debug` log out of uploaded artifacts. The job log showed the signing passwords as
  `***`, but nothing checked that an artifact gets the same treatment.
- Push a version tag after master's run for that commit has finished, not alongside it, so the
  tag run starts from master's cache.
