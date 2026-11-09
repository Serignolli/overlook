# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0]

First public release.

### Added

- Consolidated view of Web Analytics across every project in a Vercel account or team, with change against the previous period and a per-project chart.
- Project ranking with growth, drops and projects without data.
- Project view with top pages, referrers, countries, devices and custom events.
- Periods of 24 hours, 7, 30 or 90 days or a custom range; production or all environments; filters kept in the URL.
- Data from the user's own Vercel CLI (`vercel metrics`), with metric and dimension discovery through `vercel metrics schema`.
- Loopback-only server with `Host` validation, per-launch CSRF token and a strict CSP.
- Demo mode (`--demo`), system tray icon and a settings dialog.
- Self-contained packages for Windows, macOS (Intel and Apple Silicon) and Linux, plus a portable JAR.

[Unreleased]: https://github.com/Serignolli/overlook/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Serignolli/overlook/releases/tag/v0.1.0
