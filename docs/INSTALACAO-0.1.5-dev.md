# Instalação e primeira rodada — 0.1.5-dev

1. Feche o cliente e pare normalmente o Crucible. Guarde os JARs anteriores fora de `mods` para permitir retorno.
2. Instale `Akashic-Horizons-of-The-Mirai-forge17-0.1.5-dev-1.7.10.jar` em `mods` do cliente e do backend Crucible. Mantenha um único Akashic/Distant Horizons por lado. Esse JAR Forge não é destinado a `plugins`, Paper ou Velocity.
3. Preserve configurações, chaves de mundo, saves, caches e dependências. Mantenha suas opções gráficas já ajustadas para comparar o mesmo cenário. Não é necessário acrescentar companion nesta entrega.
4. Confira versão 0.1.5-dev no menu/F3 e compare o SHA-256 com o manifesto entregue.

O servidor pode disponibilizar LODs já preparados com a geração distante dedicada desativada. A sincronização deve estar permitida nas configurações negociadas; opções de banda, distância e fila continuam limitando o envio. O botão de geração no cliente não força a preparação de todo o mapa no servidor.

A importação inicial preserva seções locais criadas enquanto a resposta viajava, mesmo parciais. Ela não completa automaticamente todas as lacunas nem importa snapshots de feixes de beacon; estes continuam pelos caminhos normais de captura e sincronização. Se a seção agregada solicitada não existir no servidor, a consulta pode terminar sem dados, ainda que existam outras seções menores.

## Captura administrativa de terreno residente

Exemplo no Overworld (dimensão 0), centro em coordenadas de blocos 0,0 e raio de 8 chunks:

```text
/dh prepare loaded 0 0 0 8
/dh prepare status
/dh prepare stop
```

Para The End use o ID efetivamente registrado no seu servidor (o ID vanilla é 1). A dimensão precisa estar carregada; o comando não a abre automaticamente. Exige permissão administrativa de nível 4.

O raio aceito é de 0 a 64 chunks. `scanned` conta posições examinadas; `submitted` conta encaminhamentos ao processamento normal do DH; `unloaded`, `incomplete` e `alreadyUpdating` identificam posições puladas. `SCAN COMPLETE` significa fim da varredura, não confirmação de gravação de todos os LODs. No máximo um chunk é submetido a cada cinco ticks, com até 32 posições examinadas por tarefa e orçamento suave de 2 ms para a varredura. Operações individuais não são preemptíveis por esse orçamento.

Esse comando serve para recapturar terreno já carregado, por exemplo ao instalar o mod depois de o mundo iniciar. Não substitui uma ferramenta de pré-geração, importador de disco ou criação de chunks. Um raio grande não faz aparecer regiões que não estão residentes.

## Comparação útil

- Entre em uma região cujo cache servidor possua LODs. Fique parado e registre o preenchimento; um cliente de teste separado com cache vazio ajuda a comprovar a entrada inicial sem apagar seu cache habitual.
- Compare a mesma posição, horário, efeitos e qualidade com 0.1.4 e 0.1.5; registre também um trecho sem gravação. FPS de trajetos diferentes não isola o efeito da atualização.
- Troque entre Overworld e The End, retorne e reconecte pelo proxy. Observe especialmente dados antigos reaparecendo, travadas e continuidade visual.
- Envie os logs completos da mesma execução, configurações usadas e o momento de qualquer sintoma. Suas configurações melhoradas ainda precisam ser registradas para uma comparação de desempenho reproduzível.

A atualização não exige Java 21 no cliente. O alvo de bytecode é Java 8; as verificações locais usam Java 8u504 e Java 21.0.12.1. O Java 8u51 do cliente enviado nos logs e o driver Intel não foram executados localmente.
