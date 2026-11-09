# Fixtures de erro

Estes arquivos ainda **não** foram capturados rodando a CLI de verdade. Eles reproduzem o que o
código-fonte da Vercel CLI 63.1.0 escreve em cada situação:

| Arquivo | Origem no código da CLI |
| --- | --- |
| `rate-limited.json` | `handleApiError`, status 429, com `--format json` (stdout, exit 1) |
| `payment-required.json` | `handleApiError`, status 402 |
| `schema-unauthorized.json` | `fetchSchemaOrExit`, status 401/403 |
| `no-team.json` | `resolveQueryScope` sem time selecionado |
| `no-credentials.stderr.txt` | `promptMissingCredentials` fora de um TTY (stderr, exit 1) |

Contribuições são bem-vindas: se você reproduzir uma dessas situações, substitua o arquivo pela
saída real da CLI (com tokens, IDs de time e nomes de projeto mascarados) e registre aqui a versão
da CLI usada.
