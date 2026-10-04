# Relatório de Entrega — Akashic Mirai 0.1.17-dev

## Veredito

**APROVADA PARA TESTE CONTROLADO; STABLE PENDENTE DE RUNTIME.**

Commit final: `cffe7fdd3db08db32535c6638b020315ae1d8e43`. JAR SHA-256: `8196b43495eddb89b554a0846c8035f317df91fe38b64620304b5496240c02dc`.

## Mudança funcional

A 0.1.17 remove o `Thread.sleep(2_000)` que permanecia dentro da tarefa de IO depois do refresh de beacons. O intervalo lógico continua em 2 s após a conclusão real da consulta, agora mantido por estado monotônico por `LodQuadTree`, sem thread/timer adicional.

Consulta realmente ativa continua sendo aguardada graciosamente no shutdown. Submissões stale de picker antigo não podem executar refresh nem liberar a reserva de uma task nova.

## Evidência

Core final: 326 testes, 0 falhas, 1 skip; API 5/5. Replay explícito Java 8u411 e Java 21: core 326/0 falhas e API 5/5 em ambos. Scheduler 16/16; integração com picker real 2/2.

O primeiro full-run registrou um flake em `ClientOnlySaveStructureRetentionTest`; ele passou isoladamente e o full-run seguinte passou sem qualquer alteração nesse teste ou subsistema. A ocorrência permanece nos artefatos de evidência.

ABI Angelica 2.1.5-dev e 2.2.8 + GTNHLib 0.11.41: PASS. Identity do JAR em Java 8/21: PASS. Apenas class majors 46/51/52.

Contrato binário: 45/45 shaders byte-identical à 0.1.16; nenhuma classe removida; classes extras alteradas fora de `LodQuadTree`/`ModInfo` foram provadas byte-identical após normalizar somente a string de versão inlinada.

## Limite

O probe de laboratório demonstra que o cooldown já não ocupa o worker de IO; ele não substitui homologação no Minecraft real. O teste runtime deve repetir trocas de dimensão/backend e confirmar redução de `OLD_PICKER_SHUTDOWN`, ausência de task Beacon `TIMED_WAITING` em sleep e ausência de regressão visual/Beacon.
