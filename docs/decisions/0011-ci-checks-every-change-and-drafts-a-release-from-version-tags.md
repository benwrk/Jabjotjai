---
number: 0011
title: 'CI checks and signs every change, and a version tag on master drafts a release'
status: Accepted
component: repo
area: tooling
date: 2026-11-27
---

# 0011 — CI checks and signs every change, and a version tag on master drafts a release

## Context

The repository is public on GitHub. The owner asked for CI that builds, tests and checks pushes
and pull requests, and that publishes APKs for version tags on master, for people willing to test
the app. Every build is signed with one stable key, read from four Gradle properties (decision
`0010`).

GitHub's push filters select branches or tags: a workflow with only `tags` "won't run for events
affecting the undefined Git ref", and the documented example with both runs on a push to any
listed branch or any listed tag (docs.github.com/en/actions/reference/workflows-and-actions/
workflow-syntax, read 2026-11-27). No filter selects a tag on a particular branch.

## Decision

- `.github/workflows/ci.yml` runs `./gradlew build` on every push to master, every pull request
  and every `v*` tag. It assembles the debug and release APKs, runs the unit tests and runs lint.
  The runner is `ubuntu-24.04`, the JDK Temurin 25, matching the project JDK in
  `.idea/misc.xml`.
- The keystore and its passwords come from four repository secrets, passed to Gradle as
  `ORG_GRADLE_PROJECT_<name>` variables. The keystore file is stored base64-encoded.
- Every run reads both APKs with `apksigner` and fails unless each is signed by exactly one
  certificate, the stable key's, whose SHA-256 digest is written in the workflow. A build signed
  with any other key therefore never leaves CI.
- A push or pull request run keeps the debug APK as an artifact, named with the version and the
  commit it was built from.
- A tag run fails unless the tag is `vMAJOR.MINOR.PATCH`, its commit is already on master, and
  the `versionName` read from both APKs equals the tag without its `v` (decision `0005`). It then
  attaches both APKs to a **draft** release, marked as a prerelease before 1.0.0. The owner
  publishes it.
- The build job's token is read-only. Only the release job can write to the repository, and it
  runs none of the project's code: the build runs third-party Gradle plugins and dependencies,
  some built on request by JitPack (decision `0003`). The signing key is in the build job,
  because signing happens there.
- Every action is pinned to a full commit SHA, which GitHub's guidance recommends
  (docs.github.com/en/actions/reference/security/secure-use, read 2026-11-27).
- Gradle caching uses setup-gradle's `basic` provider, which is MIT-licensed. The default
  `enhanced` provider is a commercial library offered under different licence terms (setup-gradle
  docs at v6.4.1, read 2026-11-27).
- No instrumented tests run in CI. The only one is the template's `ExampleInstrumentedTest`, and
  running it needs an emulator.

## Rejected

**Building the release variant only on tags.** Only the release variant goes through R8 and
`lintVitalRelease`. Building it only for a tag would surface a shrinking failure when a version
is about to ship, far from the commit that caused it. Building it on every push costs one R8 run
per build.

**Signing after the build with `apksigner`, outside Gradle.** That would keep the key away from
the build's third-party code. But the build stops when the signing properties are missing
(decision `0010`), so CI would need a way to build without them, which is a switch that lets a
build behave differently from the one on the workstation. The workstation build holds the key
the same way.

**Trusting the build to have used the right key.** The certificate check reads the APK itself,
so it holds even if the signing configuration changes.

**A workflow artifact instead of a release for tags.** The owner chose a release.

**Publishing the release directly.** A draft leaves the public step with the owner.

**One job with a write token.** The build would then run third-party code holding a token that
can push to the repository.

**A third-party release action.** The runner image already has the GitHub CLI (2.104.0 on the
Ubuntu 24.04 image 20261116, read 2026-11-27), so `gh release create` needs no extra dependency.

## Consequences

- Until the four secrets are set, every run fails at the step that writes the keystore.
- A run that cannot read the secrets fails the same way, rather than producing an APK signed with
  another key.
- A tag pushed before master fails its run. Push master, then re-run the job.
- Replacing the stable key means changing the secrets and `SIGNING_CERT_SHA256` together.
- CI catches release builds that fail to build. A release APK that builds but breaks at runtime
  under R8 is caught only on a device.
