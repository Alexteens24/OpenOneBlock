package dev.openoneblock.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

class PaperIslandCommandGatewayTest {
  @Test
  void unavailableRuntimeStillReturnsTraceableFailedSubmission() {
    OperationId operationId = OperationId.parse("00000000-0000-0000-0000-0000000000d1");
    Server server =
        (Server)
            Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, arguments) -> null);
    PaperIslandCommandGateway gateway =
        new PaperIslandCommandGateway(
            server,
            Optional::empty,
            () -> IslandId.parse("00000000-0000-0000-0000-0000000000d2"),
            () -> operationId);

    MutationSubmission<?> submission =
        gateway.create(PlayerId.of(UUID.fromString("00000000-0000-0000-0000-0000000000d3")));

    assertEquals(operationId, submission.operationId());
    CompletionException failure =
        assertThrows(
            CompletionException.class, () -> submission.completion().toCompletableFuture().join());
    assertInstanceOf(CommandRuntimeUnavailableException.class, failure.getCause());
  }
}
