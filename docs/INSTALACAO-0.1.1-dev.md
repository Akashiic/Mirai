# Substituição da build — Mirai 0.1.1-dev

Esta versão corrige o conflito fastutil que derrubou o cliente durante a inicialização de Angelica/GTNHLib. Consulte o relatório de entrega para o hash do JAR e o alcance dos testes.

1. Feche completamente o Minecraft. Coremods exigem uma nova inicialização do processo.
2. Retire de `mods` o arquivo `Akashic-Horizons-of-The-Mirai-forge17-0.1.0-dev-1.7.10.jar`.
3. Coloque em `mods` somente `Akashic-Horizons-of-The-Mirai-forge17-0.1.1-dev-1.7.10.jar`.
4. Preserve Angelica 2.2.8, GTNHLib 0.11.41, Hodgepodge e UniMixins da instância testada. Mantenha LWJGL2 e o perfil Java existente para esse comparativo.
5. Inicie o cliente e confira se chega ao menu principal. Depois, abra uma cópia de mundo ou conecte ao servidor de homologação e confira a renderização.

O identificador continua `distanthorizons`: não deixe o Mirai anterior, outro DH ou outro fork desse mesmo mod instalados simultaneamente. O arquivo vai em `mods`, não em `plugins`.

Se o servidor também tem Mirai, substitua o JAR nele durante uma parada normal e use 0.1.1-dev nos dois lados. A verificação Forge compara a versão. Um cliente Mirai pode conectar a servidor sem Mirai, limitado aos dados disponíveis para ele.

Não é necessário apagar `DistantHorizons.toml`, caches LOD, configurações de bibliotecas ou saves para corrigir esta colisão. O hotfix isola a cópia interna do Mirai; a biblioteca moderna solicitada pelo GTNHLib continua sendo usada pelos outros mods.

O log recebido continua identificando Java 1.8.0_51 no cliente; Java 21 é o alvo declarado do servidor Crucible. Este hotfix não pressupõe que esses processos usem o mesmo runtime.

Se houver outra falha, preserve o novo `latest.log` e o crash report completos. O erro original esperado para comparação é `NoSuchMethodError: ObjectArrayList.combine` em `ObjectImmutableList.<clinit>`. A ausência desse erro permite avançar a homologação, mas não comprova todos os caminhos do modpack.

Para retorno, feche o processo e retire a 0.1.1-dev. Reinstalar a 0.1.0-dev pode reproduzir a colisão já confirmada; para comparar com uma baseline funcional, use a cópia sem Mirai preservada antes do primeiro ensaio.
