# Instalação da Mirai 0.1.2-dev

1. Feche completamente o Minecraft, inclusive se CoreTweaks manteve uma janela branca após o crash.
2. Remova de `mods` o JAR Mirai 0.1.1-dev e coloque `Akashic-Horizons-of-The-Mirai-forge17-0.1.2-dev-1.7.10.jar`.
3. Mantenha somente uma versão do Mirai/Distant Horizons. Preserve Angelica, GTNHLib, Hodgepodge, UniMixins e LWJGL2 nas versões atuais durante este teste.
4. Inicie o cliente, entre pelo proxy no lobby Paper e confira a imagem do mundo, movimentação e ausência da exceção `GLStateManager.getFogColor` no novo log.
5. Depois, teste a transição para Forbidden/Crucible e o retorno ao lobby, mantendo os logs completos caso haja outra falha.

Esta correção não exige apagar configurações, cache de LODs ou saves. Nenhum JAR adicional vai em `plugins` do lobby. Se o Mirai já estiver instalado no Crucible, substitua-o durante uma parada normal e mantenha a mesma versão nos dois lados; não é necessário instalá-lo no servidor apenas para corrigir essa exceção do cliente.

O log recebido utiliza Java 8u51 no cliente. O Java 21 do Crucible pertence a outro processo. O artefato é produzido para bytecode Java 8; consulte no relatório as JVMs efetivamente usadas nos testes.

A correção é destinada à próxima rodada de homologação. A chegada ao menu, os testes isolados e a auditoria do binário não substituem a execução da entrada no mundo com o pack real.
