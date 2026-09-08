# Akashic: Horizons of The Mirai — 0.1.6-dev

Base: 0.1.5-dev, raiz `5e10599fc91a8cb8e0b9d269c6c59a4651daa922`, core `a1be0dbc7d4a4f9ab33f0927bf53a17ccc2845d9`.

Esta candidata acrescenta diagnóstico local para relacionar ambiente, configurações, ciclo de vida, renderização, trabalho, rede e cache durante os testes. Não altera qualidade visual, shaders, formato de persistência ou protocolo como estratégia de desempenho.

- Novo `logs/akashic-mirai/latest.log` automático, fila limitada, rotação, metadados por sessão e erros limitados. Falha de escrita fica isolada do jogo.
- `/miraidiag status|mark|trace|stop` no cliente e `/dh diagnostics ...` para administração do servidor. Trace dura de 10 a 120 segundos e não atribui culpa a um mod pela presença de uma classe em uma pilha.
- Contadores e histogramas nos caminhos existentes, com tempos decorrido/parede identificados. Consultas de cobertura usam valores já calculados; observações de filas não invocam callbacks do escalonador e param em 256 entradas vivas.
- Ambiente e opções capturados por uma lista permitida, sem envio automático, mensagens arbitrárias de exceções, chat, credenciais ou endereços. Threads de escrita não retêm mundos, pacotes ou objetos de exceção.
- Onze testes de regressão do logger: saturação, concorrência, rotação, escape/limites, falhas de IO, symlinks, exclusão de segundo escritor, histogramas, repetição de erros e comandos/amostragem.

Consulte [o guia de diagnóstico](DIAGNOSTICO-MIRAI.md). O relatório que acompanha o JAR registra a compilação, os testes executados e os limites do parecer final. Aprovação para uma rodada controlada de testes não equivale à homologação do modpack/driver.

Horizonte branco, cortes visuais, transições, ordenação de transparência e desempenho real continuam dependendo da reprodução no ambiente do usuário. O diagnóstico ajuda a localizar a causa; esta entrega não afirma ter corrigido todos esses sintomas.
