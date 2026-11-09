# Security Policy

Overlook runs commands against your Vercel account through your own Vercel CLI, so security reports are taken seriously.

## Supported versions

Only the [latest release](https://github.com/Serignolli/overlook/releases/latest) receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue**. Instead, report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/Serignolli/overlook/security/advisories/new).

Include:

- what the issue is and its impact,
- steps to reproduce (OS, Overlook version, `vercel --version`),
- any proof of concept, with tokens and account details removed.

You should get a first response within a week. Once a fix is released, the advisory will be published and you will be credited unless you prefer otherwise.

## In scope

Things Overlook promises and that are worth reporting if broken:

- credentials or tokens leaking to logs, the UI, the process list or the network;
- the local server being reachable from anything other than `127.0.0.1`, or bypasses of the `Host` and CSRF checks;
- the app making network requests of its own (other than the Vercel CLI subprocess);
- running anything other than the Vercel CLI, or passing it unexpected arguments.

See [Privacy](README.md#privacy) for the full list of guarantees and where each one is implemented.

## Out of scope

- Vulnerabilities in the Vercel CLI or Vercel's platform: report them to [Vercel](https://vercel.com/security).
- Issues that require an attacker who already controls your user account or machine.
- The landing page in `site/`, unless it serves something harmful.
