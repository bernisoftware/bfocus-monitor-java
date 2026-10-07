# bFocus Monitor — Java

Captura os erros não tratados do seu sistema Java e manda para o **bFocus**, onde o mesmo erro é agrupado
entre todos os clientes e vira demanda para a equipe (módulo Monitoramento). Java 11+, **zero dependências**
de runtime (`java.net.http`, `javax.crypto` e JSON escrito à mão).

## Instalar

```xml
<dependency>
  <groupId>br.com.bernisoftware</groupId>
  <artifactId>bfocus-monitor</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("br.com.bernisoftware:bfocus-monitor:0.1.0") // Gradle
```

## Ligar (uma linha)

```java
import br.com.bernisoftware.bfocus.monitor.BfocusMonitor;
import br.com.bernisoftware.bfocus.monitor.MonitorOptions;

BfocusMonitor.init(MonitorOptions.builder().key("bf_mon_…").release("1.4.2").environment("production").build());
```

A chave está em **Monitoramento → Agentes** no bFocus. O `init` não faz chamada de rede na sua thread: instala
`Thread.setDefaultUncaughtExceptionHandler` (nível `fatal`, até 2 s para enviar; depois o handler que já
existia continua rodando — o app quebra exatamente como quebraria sem o monitor) e um shutdown hook que
envia o que estiver na fila.

## Spring Boot 3 / Servlet (jakarta)

```java
@Bean
FilterRegistrationBean<BfocusMonitorFilter> bfocusMonitorFilter() {
    FilterRegistrationBean<BfocusMonitorFilter> reg = new FilterRegistrationBean<>(new BfocusMonitorFilter());
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return reg;
}
```

Fora do Spring, registre `br.com.bernisoftware.bfocus.monitor.BfocusMonitorFilter` no `web.xml` ou com
`@WebFilter`. O filtro captura a exceção que escapar da requisição e **relança**; o evento leva
`transaction` (`"POST /pedidos/42"`) e a URL **sem a query string**. Erro que um `@ControllerAdvice` já
tratou não chega até ele — capture à mão onde tratar. A API de Servlet é `provided`: vem do seu container.
(Só `jakarta.servlet`; Spring Boot 2/`javax.servlet` não é suportado nesta versão.)

## Quem foi afetado (identidade assinada)

O bFocus só liga o erro a cliente e pessoa com assinatura válida — a mesma do `userHash` do widget. No
servidor, dê o segredo da chave de assinatura do sistema e o pacote assina sozinho:

```java
BfocusMonitor.init(MonitorOptions.builder()
    .key("bf_mon_…").release("1.4.2")
    .signingSecret(System.getenv("BFOCUS_SIGNING_SECRET"))   // só no servidor
    .build());

// no login / no começo da requisição:
BfocusMonitor.setUser(usuario.getId(), usuario.getClienteId());
```

Dentro de uma requisição (`BfocusMonitorFilter`), `setUser`, `setTag` e `addBreadcrumb` valem **só para
ela** (ThreadLocal, limpo no fim da requisição): dois usuários simultâneos nunca trocam de identidade. Em app
desktop, quem tem o segredo é o seu servidor — passe o hash pronto: `setUser(id, clienteId, userHash)`.

Fora de requisição (filas, jobs), abra o contexto à mão:

```java
try (MonitorScope scope = BfocusMonitor.beginScope("job FecharCaixa", null)) {
    BfocusMonitor.setUser(job.usuarioId(), job.clienteId());
    …
}
```

## Captura manual

```java
try { … } catch (Exception e) {
    BfocusMonitor.captureException(e, Level.ERROR, Map.of("modulo", "fiscal"), List.of("nota-fiscal", "timeout"));
}

BfocusMonitor.captureMessage("estoque negativo", Level.WARNING);
BfocusMonitor.setTag("filial", "POA");
BfocusMonitor.addBreadcrumb("http", "GET /api/estoque 500", Level.ERROR);

BfocusMonitor.flush(Duration.ofSeconds(2));   // CLI/serverless: espera o envio (até o teto)
BfocusMonitor.close();                        // envia o que falta e desliga
```

Exceção encadeada (`getCause()`): vai o tipo e a mensagem da mais **interna** (o grupo é da causa
raiz), com `" (dentro de: <TipoExterno>: <mensagem externa>)"` no fim — só o tipo externo quando a
mensagem externa já contém a interna.

## Opções (`MonitorOptions.builder()`)

| Opção | Padrão | |
| --- | --- | --- |
| `key` | — | Obrigatória (`bf_mon_…`). Vazia → `IllegalArgumentException`. |
| `release` | — | Versão do seu sistema; liga o erro às notas de versão. |
| `environment` | `production` | |
| `baseUrl` | `https://api.bfocus.com.br` | |
| `sampleRate` | `1.0` | Fração enviada (0 a 1). |
| `ignore(String...)` / `ignore(Pattern...)` | vazio | Mensagens a ignorar (texto contido / expressão). |
| `beforeSend` | — | `UnaryOperator<MonitorEvent>`: devolva o evento alterado ou `null` para descartar. |
| `signingSecret` | — | Só servidor: assina a identidade no `setUser` (renova depois de 6 dias). |
| `autoCapture` | `true` | Instalar o gancho de exceção não tratada. |
| `inAppPrefixes` | vazio | Pacotes que são do seu sistema quando a heurística não basta (`java.`, `javax.`, `jdk.`, `sun.`, `kotlin.`, `org.springframework.`… contam como biblioteca). |

## Como envia

- Fila em memória (até 100 eventos; cheia → descarta o mais novo) e uma thread daemon que manda em lote a
  cada 1 s ou 20 eventos. Nada aqui lança exceção para o seu código.
- 429, 5xx e erro de rede: uma nova tentativa depois de 2 s. 401/403 (chave errada, agente desligado):
  para de enviar até o próximo `init`.
- O mesmo erro sai no máximo 1 vez a cada 30 s, e no máximo 100 eventos por minuto.
- Sinal de vida: logo depois do `init` (em segundo plano) e a cada 5 min, um `POST /api/v1/monitor/heartbeat`
  com a instância (hash curto de máquina + pid), a máquina, release e ambiente — é como o painel mostra o
  agente **vivo** mesmo sem erro nenhum. Thread daemon; `flush`/`close` não mandam sinal.
- Frames: `file` = pacote como caminho + arquivo (`com/acme/Pedido.java`), `function` = `Classe.metodo`.
- Não manda corpo de requisição, cookies, headers nem query string.

## Licença

MIT — Berni Software.
