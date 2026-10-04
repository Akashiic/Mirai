# Teste runtime controlado

1. Faça backup do JAR Mirai atual fora de `mods`.
2. Use somente um JAR Distant Horizons/Mirai no cliente.
3. Preserve o `DistantHorizons.toml` atual com `rendererMode = "DISABLED"`.
4. Entre no mesmo backend/rota onde ocorreram os freezes e reproduza movimento/voo pelo mesmo tipo de área.
5. Não altere distância de visão, resource pack, Angelica ou outros mods durante o A/B.

## Coleta DISABLED

Após o login estabilizar, use `/miraidiag trace 60` e jogue normalmente durante a janela.

No log `logs/akashic-mirai/latest.log`, a expectativa após o pequeno tail de trabalho que já estava em voo é:

- `LOD_RENDER_WALL` = 0;
- uploads GL = 0;
- `CHUNK_SUBMITTED` próximo de 0;
- queda forte de `DB_COMMITTED`/`LOD_UPDATE_WALL` derivados de chunk ingestion;
- `COLD_REQUEST`/`REFRESH_REQUEST` próximos de 0 enquanto DISABLED;
- `QUIESCENT_CHUNK_DEFERRED` > 0 ao atravessar chunks novos;
- `QUIESCENT_REMOTE_SYNC_SUPPRESSED` pode crescer;
- `QUIESCENT_PARTIAL_UPDATE_SUPPRESSED` pode crescer se o servidor enviar partial LODs recuperáveis.

## Teste de reativação

Depois do teste DISABLED, altere o renderer para DEFAULT sem trocar de mundo e continue jogando.

Esperado:

- o renderer volta a funcionar normalmente;
- `QUIESCENT_CATCHUP_SUBMITTED` cresce lentamente;
- catch-up limitado a no máximo 1 chunk residente a cada 5 ticks;
- nenhuma geração/load de chunk é provocada pelo catch-up;
- nenhuma explosão instantânea de milhares de chunks ao reativar.

Depois retorne a DISABLED e confirme que os contadores de trabalho voltam a cair.

## Comparação de stutter

Compare a quantidade e magnitude de `Warning: Clientside chunk ticking took ... ms` no `latest.log` vanilla entre baseline e candidato, usando rota semelhante.

O critério de sucesso do patch é reduzir/remover a parcela de stutter causada pelo Mirai enquanto DISABLED. Stutters que permanecerem com counters Mirai quiescentes devem ser investigados fora do Mirai, não mascarados por este patch.

Se houver regressão, volte ao JAR anterior ou use `-Dakashic.mirai.disabledRendererQuiescence=false` e repita a mesma cena.
