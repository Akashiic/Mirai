# Akashic DH EndlessIDs Compat 1.0.1

Patch server-side para Minecraft 1.7.10 que corrige a incompatibilidade entre Akashic: Horizons of The Mirai / Distant Horizons e a extensão de biomas do EndlessIDs.

## Escopo

O EndlessIDs substitui o array vanilla de biomas de `byte[256]` por armazenamento estendido e, por segurança, lança uma exceção quando outro mod chama `Chunk.getBiomeArray()`.

O Mirai 0.1.16-dev e 0.1.17-dev usam essa API vanilla somente como validação em dois pontos:

- `MixinChunkMapCommon#onChunkSave`: 1 chamada.
- `LegacyLoadedTerrainPreparation#scanBatch`: 2 chamadas.

O processamento real de biomas do Mirai 1.7.10 continua usando `World.getBiomeGenForCoords(...)` através de `ChunkWrapper`.

## Estratégia do patch

O coremod transforma somente as três invocações de validação acima e as redireciona para um marcador interno `byte[256]` não utilizado como dados de bioma.

O patch NÃO:

- altera IDs de bioma;
- converte os IDs estendidos para byte;
- modifica NBT ou formato de chunk;
- desativa a proteção do EndlessIDs;
- registra canal de rede;
- exige instalação no cliente;
- altera o pipeline real de leitura de biomas do Mirai.

## Fail-closed

O transformer só aceita os nomes de classe, métodos, owner, descriptor e quantidade de call-sites conhecidos. Se encontrar uma versão futura com uma forma diferente, preserva o bytecode original em vez de aplicar uma transformação ambígua.

Se o EndlessIDs não for detectado no classpath, nenhuma classe do Mirai é modificada.

## Validação realizada

O JAR foi testado offline contra os artefatos exatos:

- Akashic Mirai 0.1.16-dev Forge 1.7.10;
- Akashic Mirai 0.1.17-dev Forge 1.7.10.

Para ambos foram validados 1 call-site no save hook e 2 no scanner. Após a transformação restaram zero chamadas a `Chunk.getBiomeArray()/func_76605_m()` nesses métodos. A transformação também foi aplicada uma segunda vez para confirmar idempotência.

O patch é compilado para Java 8 bytecode (`major version 52`) e pode executar no stack Java 21/RFB usado pelo servidor.

## Instalação

1. Faça backup do mundo antes do primeiro teste, principalmente porque o próprio EndlessIDs altera o formato de armazenamento quando extensões estão habilitadas.
2. Coloque `Akashic-DH-EndlessIDs-Compat-1.0.1.jar` somente na pasta `mods` do servidor 1.7.10.
3. Mantenha o EndlessIDs e o Akashic Mirai instalados normalmente.
4. Não instale este patch no cliente.
5. Reinicie completamente o servidor; não use reload.

Na inicialização, procure pelos logs `AkashicDHEndlessIDsCompat` informando 1 chamada corrigida no save hook e 2 no scanner.

## Rollback

O patch não grava formato próprio e não requer migração. Para removê-lo, primeiro use uma versão do Mirai que possua compatibilidade nativa com EndlessIDs; remover apenas o patch mantendo Mirai 0.1.16/0.1.17 reintroduz a chamada incompatível.

## Limite da homologação

Os gates estáticos e de bytecode reduzem fortemente o risco de regressão, mas não substituem um teste runtime no servidor real com os mods e o mundo de produção. A primeira execução deve ser feita com backup e acompanhada pelos logs de save de chunks.

## Gate de ativação runtime

O `LaunchClassLoader` também foi testado em dois cenários para 0.1.16 e 0.1.17: sem recurso do EndlessIDs, o transformer preserva a classe original; com o recurso do EndlessIDs presente, ele ativa o patch e remove a chamada incompatível esperada. Esse teste faz parte de `verify.ps1`.
