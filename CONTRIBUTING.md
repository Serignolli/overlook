# Contributing to Overlook

Thanks for your interest in improving Overlook! Bug reports, ideas, docs fixes and pull requests are all welcome. By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Prerequisites

- Java 21+
- Maven 3.9+
- Optional: [Vercel CLI](https://vercel.com/docs/cli) logged in, to test against real data

## Build locally

```bash
mvn verify                              # build + all tests
java -jar target/overlook.jar --demo    # fake data, no CLI needed
java -jar target/overlook.jar           # real data from your Vercel CLI
```

`--demo` is the fastest way to work on the UI: it serves fixture data from `src/main/resources/demo/` and never runs the CLI.

## Project layout

```
src/main/java/dev/overlook/
  App.java                # entry point: flags, server, browser, tray
  Tray.java               # system tray icon (Open dashboard / Quit)
  config/                 # config.json and data folder
  vercel/                 # the ONLY package that knows the CLI exists
    CliRunner.java        # runs the CLI: arg list, env allowlist, timeout
    CliLocator.java       # finds the CLI (PATH, Homebrew, nvm, Volta...)
    CliVercelGateway.java # vercel metrics / teams ls / project ls
    FixtureVercelGateway  # --demo and tests
    parse/                # JSON parsers for each CLI command
    error/                # typed errors shown in the UI
  analytics/              # aggregation rules, time ranges, cache, service
  web/                    # Javalin server, API routes, Host/CSRF guards
src/main/resources/web/   # dashboard UI (plain JS modules, uPlot vendored)
src/test/                 # unit, API and architecture tests + CLI fixtures
packaging/                # icons and the jlink/jpackage script
site/                     # landing page (static, deployed to Vercel)
.github/
  workflows/ci.yml        # build and test on Windows, macOS and Linux
  workflows/release.yml   # self-contained packages on tag push
  ISSUE_TEMPLATE/         # bug report and feature request forms
```

## Ground rules

- **Only `dev.overlook.vercel` runs processes or knows about the CLI.** Swapping the CLI for an official API should touch one package. `ArchitectureTest` enforces this.
- **No outbound network from the app.** No HTTP clients, sockets or `URL.openConnection`. Also enforced by `ArchitectureTest`.
- **No external assets in the dashboard.** No CDNs, web fonts or remote scripts; the CSP is `default-src 'self'`. Third-party JS is vendored and listed in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
- **Never hardcode metric or dimension names** without a fallback: discover them through `vercel metrics schema`.
- **Unique counts are never summed** across days or presented as totals across projects. See `AggregationRules`.
- **Don't guess CLI messages.** Error fixtures in `src/test/resources/fixtures/vercel/errors/` should come from real CLI runs, with secrets masked. See the README in that folder.
- UI strings are in Portuguese (pt-BR); code comments follow the language of the surrounding file.

## Making a change

1. For anything bigger than a small fix, open an issue first so we can agree on the approach.
2. Fork the repository and create a branch (`git checkout -b feat/my-feature`).
3. Make your changes, with tests.
4. Run `mvn verify` and try the app with `--demo` (and with real data if you can).
5. Open a pull request describing what changed and why. Screenshots help for UI changes.

CI runs `mvn verify` on Windows, macOS and Linux for every pull request.

## The landing page

`site/` is a static page with no build step. Open `site/index.html` in a browser, or serve the folder with any static server (for example `npx serve site`). It is deployed on Vercel with **Root Directory** set to `site`; `site/vercel.json` holds the headers and skips deploys when nothing under `site/` changed.

## Releasing

1. Update [CHANGELOG.md](CHANGELOG.md).
2. Push a tag like `v0.2.0`.

The release workflow builds `overlook-<platform>.zip` / `.tar.gz` on each OS with a bundled runtime, plus `overlook.jar`, and attaches them with SHA-256 checksums to the GitHub release. The version comes from the tag (`-Drevision`), so `pom.xml` doesn't need editing.

To regenerate the icons after changing the design: `java packaging/IconGen.java`.

## Reporting bugs

Open an [issue](https://github.com/Serignolli/overlook/issues/new/choose) and include your OS, the output of `vercel --version`, and the relevant lines from `logs/overlook.log` in your data folder (`~/.overlook/` or `%APPDATA%\overlook\`). Tokens are masked in that log, but give it a quick look before sharing.

Security issues should **not** go in public issues; see [SECURITY.md](SECURITY.md).
