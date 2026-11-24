# Overlook — plano, dados extras e histórico local (2026-11-24)

## Já feito (rodada anterior)
- [x] Apoie: link https://apoie.serignolli.com/ no app, no site e no README
- [x] Paralelismo 6, timeout 60 s, cache com chave estável, TTL 1 h para período fechado
- [x] 30d sem comparação no Hobby, 90d com erro claro, eventos bloqueados por plano
- [x] Zoom: arrastar no gráfico diário abre os dias por hora; no gráfico por hora aproxima no lugar

## Plano da conta
- [x] Limite por plano a partir de `teams ls` (hobby 31 d, pro 365 d, enterprise 730 d; fonte: vercel.com/docs/analytics/limits-and-pricing, atualizada em 25/08/2026), sem passar do limite aprendido com erro
- [x] Endpoint `/api/limits` (plano, dias, histórico)
- [x] UI: plano no cabeçalho, períodos além do limite desabilitados quando não há histórico local, data mínima no período personalizado

## Mais dados da CLI
- [x] Dimensões novas: sistema, navegador, domínio, UTM source/medium/campaign (testado no Hobby: todas respondem)
- [x] Painéis extras em "Mais detalhes", carregados só ao abrir (não pesar a tela)
- [x] UTM: ignorar linhas sem UTM, com aviso próprio

## Histórico local (opcional, com consentimento)
- [x] `HistoryStore`: arquivo JSON por escopo em `<dados>/history/`, cobertura + valores
- [x] Gravar buckets fechados de toda consulta de série (só se ativado; nunca no modo demo)
- [x] Ler: períodos além do limite do plano juntam histórico local + Vercel, com aviso de lacunas
- [x] Comparação com período anterior usando o histórico quando ele cobre tudo
- [x] Visitantes únicos: não somáveis entre dias, card indisponível quando o período usa histórico
- [x] Retenção do histórico (sem limite, 3, 6, 12, 24 meses) com poda
- [x] API: `GET /api/history` (uso, cobertura), `DELETE /api/history` (limpar)
- [x] UI nas configurações: ativar (consentimento), retenção, uso em disco, botão limpar com confirmação em dois passos
- [x] Sugerir ativar o histórico quando bater no limite do plano

## Site e docs
- [x] Site: recurso de histórico local e dados extras
- [x] README: features e limitações atualizadas

## Verificação
- [x] `mvn package` com testes verdes
- [x] App real: limites, painéis extras, gravar/ler histórico, limpar

## Encontrado no caminho
- [x] Hobby: cada carga de 30d com histórico ligado faz 1 consulta extra (o dia que falta do período anterior); aceito, porque esse dia também vai para o histórico
- [x] A CLI marca a saída com `<claude-code-hint>` quando roda dentro do Claude Code; no Overlook o ambiente é filtrado e isso não aparece
