package dev.openoneblock.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandDeletionResult;
import dev.openoneblock.core.island.IslandHomeResult;
import dev.openoneblock.core.island.IslandInfoSnapshot;
import dev.openoneblock.core.island.IslandMembershipConflictException;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.paper.bootstrap.PluginRuntimeLifecycle;
import dev.openoneblock.paper.bootstrap.PluginRuntimeState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class OpenOneBlockCommandTest {
  private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final IslandId ISLAND_ID = IslandId.parse("00000000-0000-0000-0000-0000000000c2");
  private static final OperationId OPERATION_ID =
      OperationId.parse("00000000-0000-0000-0000-0000000000c3");

  @Test
  void rejectsCreateBeforeReadyWithoutCallingGateway() {
    PluginRuntimeLifecycle lifecycle = bootstrapping();
    AtomicInteger calls = new AtomicInteger();
    RecordingMessenger messages = new RecordingMessenger();
    OpenOneBlockCommand command =
        command(
            lifecycle,
            ignored -> {
              calls.incrementAndGet();
              throw new AssertionError("gateway must not run before READY");
            },
            messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"create"});

    assertEquals(0, calls.get());
    assertEquals(List.of("command.not-ready"), messages.keys());
  }

  @Test
  void rejectsConsoleCreateExplicitly() {
    RecordingMessenger messages = new RecordingMessenger();
    OpenOneBlockCommand command =
        command(ready(), ignored -> completedSubmission(activeResult(false)), messages);

    command.execute(
        source(sender(Set.of(OpenOneBlockPermissions.COMMAND))), new String[] {"create"});

    assertEquals(List.of("command.player-only"), messages.keys());
  }

  @Test
  void createReturnsBeforeAsynchronousCompletionAndThenReportsSuccess() {
    CompletableFuture<CreateIslandResult> pending = new CompletableFuture<>();
    RecordingMessenger messages = new RecordingMessenger();
    AtomicInteger calls = new AtomicInteger();
    OpenOneBlockCommand command =
        command(
            ready(),
            owner -> {
              calls.incrementAndGet();
              assertEquals(PlayerId.of(PLAYER_UUID), owner);
              return new MutationSubmission<>(OPERATION_ID, pending);
            },
            messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"create"});

    assertEquals(1, calls.get());
    assertEquals(List.of("command.create.started"), messages.keys());
    assertFalse(pending.isDone());

    pending.complete(activeResult(false));

    assertEquals(List.of("command.create.started", "command.create.success"), messages.keys());
    assertEquals(OPERATION_ID, messages.entries().getFirst().placeholders().get("operation_id"));
    assertEquals(ISLAND_ID, messages.entries().get(1).placeholders().get("island_id"));
  }

  @Test
  void mapsMembershipConflictWithoutOperatorError() {
    RecordingMessenger messages = new RecordingMessenger();
    CompletableFuture<CreateIslandResult> failed =
        CompletableFuture.failedFuture(
            new IslandMembershipConflictException(PlayerId.of(PLAYER_UUID), ISLAND_ID));
    OpenOneBlockCommand command =
        command(ready(), ignored -> new MutationSubmission<>(OPERATION_ID, failed), messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"create"});

    assertEquals(
        List.of("command.create.started", "command.create.already-member"), messages.keys());
    assertEquals(ISLAND_ID, messages.entries().get(1).placeholders().get("island_id"));
  }

  @Test
  void enforcesSubcommandPermissionAndFiltersSuggestions() {
    RecordingMessenger messages = new RecordingMessenger();
    Player player = player(Set.of(OpenOneBlockPermissions.COMMAND, OpenOneBlockPermissions.HELP));
    OpenOneBlockCommand command =
        command(ready(), ignored -> completedSubmission(activeResult(false)), messages);

    command.execute(source(player), new String[] {"create"});

    assertEquals(List.of("command.no-permission"), messages.keys());
    assertEquals(List.of("help"), command.suggest(source(player), new String[] {""}));
  }

  @Test
  void rootAndUnknownCommandsUseLocaleKeys() {
    RecordingMessenger messages = new RecordingMessenger();
    OpenOneBlockCommand command =
        command(bootstrapping(), ignored -> completedSubmission(activeResult(false)), messages);
    CommandSourceStack source = source(sender(allPlayerPermissions()));

    command.execute(source, new String[0]);
    command.execute(source, new String[] {"does-not-exist"});

    assertEquals(List.of("command.help", "command.unknown"), messages.keys());
  }

  @Test
  void homeIsNonBlockingAndReportsOperationOutcome() {
    CompletableFuture<IslandHomeResult> pending = new CompletableFuture<>();
    RecordingMessenger messages = new RecordingMessenger();
    IslandCommandGateway gateway =
        new IslandCommandGateway() {
          @Override
          public MutationSubmission<CreateIslandResult> create(PlayerId owner) {
            return completedSubmission(activeResult(false));
          }

          @Override
          public MutationSubmission<IslandHomeResult> home(PlayerId player) {
            return new MutationSubmission<>(OPERATION_ID, pending);
          }
        };
    OpenOneBlockCommand command = command(ready(), gateway, messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"home"});

    assertEquals(List.of("command.home.started"), messages.keys());
    assertFalse(pending.isDone());
    pending.complete(new IslandHomeResult(ISLAND_ID, OPERATION_ID, 9));
    assertEquals(List.of("command.home.started", "command.home.success"), messages.keys());
  }

  @Test
  void infoRendersOnlyAfterAsynchronousQueryCompletes() {
    CompletableFuture<IslandInfoSnapshot> pending = new CompletableFuture<>();
    RecordingMessenger messages = new RecordingMessenger();
    IslandCommandGateway gateway =
        new IslandCommandGateway() {
          @Override
          public MutationSubmission<CreateIslandResult> create(PlayerId owner) {
            return completedSubmission(activeResult(false));
          }

          @Override
          public java.util.concurrent.CompletionStage<IslandInfoSnapshot> info(PlayerId player) {
            return pending;
          }
        };
    OpenOneBlockCommand command = command(ready(), gateway, messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"info"});

    assertEquals(List.of(), messages.keys());
    pending.complete(
        new IslandInfoSnapshot(
            ISLAND_ID,
            PlayerId.of(PLAYER_UUID),
            dev.openoneblock.api.id.NamespacedId.parse("openoneblock:owner"),
            ShardGroupId.parse("openoneblock:primary"),
            new GridPosition(0, 0),
            64,
            384,
            dev.openoneblock.api.id.NamespacedId.parse("openoneblock:plains"),
            0,
            0,
            1,
            9));
    assertEquals(List.of("command.info"), messages.keys());
    assertEquals(ISLAND_ID, messages.entries().getFirst().placeholders().get("island_id"));
  }

  @Test
  void deleteFirstIssuesExactConfirmationWithoutMutation() {
    CompletableFuture<ConfirmationChallenge> pending = new CompletableFuture<>();
    RecordingMessenger messages = new RecordingMessenger();
    IslandCommandGateway gateway =
        new IslandCommandGateway() {
          @Override
          public MutationSubmission<CreateIslandResult> create(PlayerId owner) {
            return completedSubmission(activeResult(false));
          }

          @Override
          public java.util.concurrent.CompletionStage<ConfirmationChallenge> requestDelete(
              PlayerId player) {
            return pending;
          }
        };
    OpenOneBlockCommand command = command(ready(), gateway, messages);

    command.execute(source(player(allPlayerPermissions())), new String[] {"delete"});

    assertEquals(List.of(), messages.keys());
    pending.complete(
        new ConfirmationChallenge(
            "delete-token",
            ConfirmationAction.DELETE,
            PlayerId.of(PLAYER_UUID),
            ISLAND_ID,
            9,
            Instant.parse("2026-07-19T00:00:30Z")));
    assertEquals(List.of("command.delete.confirm"), messages.keys());
    assertEquals("delete-token", messages.entries().getFirst().placeholders().get("token"));
  }

  @Test
  void confirmedDeleteReturnsBeforeDurableOperationCompletes() {
    CompletableFuture<IslandDeletionResult> pending = new CompletableFuture<>();
    RecordingMessenger messages = new RecordingMessenger();
    IslandCommandGateway gateway =
        new IslandCommandGateway() {
          @Override
          public MutationSubmission<CreateIslandResult> create(PlayerId owner) {
            return completedSubmission(activeResult(false));
          }

          @Override
          public MutationSubmission<IslandDeletionResult> confirmDelete(
              PlayerId player, String token) {
            assertEquals("delete-token", token);
            return new MutationSubmission<>(OPERATION_ID, pending);
          }
        };
    OpenOneBlockCommand command = command(ready(), gateway, messages);

    command.execute(
        source(player(allPlayerPermissions())), new String[] {"delete", "confirm", "delete-token"});

    assertEquals(List.of("command.delete.started"), messages.keys());
    assertFalse(pending.isDone());
    pending.complete(new IslandDeletionResult(ISLAND_ID, OPERATION_ID, false));
    assertEquals(List.of("command.delete.started", "command.delete.success"), messages.keys());
  }

  private static MutationSubmission<CreateIslandResult> completedSubmission(
      CreateIslandResult result) {
    return new MutationSubmission<>(OPERATION_ID, CompletableFuture.completedFuture(result));
  }

  private static OpenOneBlockCommand command(
      PluginRuntimeLifecycle lifecycle, IslandCommandGateway gateway, RecordingMessenger messages) {
    return new OpenOneBlockCommand(
        lifecycle,
        gateway,
        messages,
        new CommandFailureMapper(),
        Logger.getLogger("OpenOneBlockCommandTest"));
  }

  private static PluginRuntimeLifecycle bootstrapping() {
    PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();
    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);
    return lifecycle;
  }

  private static PluginRuntimeLifecycle ready() {
    PluginRuntimeLifecycle lifecycle = bootstrapping();
    lifecycle.transitionTo(PluginRuntimeState.RECOVERING);
    lifecycle.transitionTo(PluginRuntimeState.READY);
    return lifecycle;
  }

  private static CreateIslandResult activeResult(boolean replay) {
    Instant now = Instant.parse("2026-07-19T00:00:00Z");
    AllocatedSlot slot =
        new AllocatedSlot(
            SlotId.generate(),
            ShardGroupId.parse("openoneblock:primary"),
            0,
            new GridPosition(0, 0),
            SlotState.ACTIVE,
            ISLAND_ID,
            2);
    IslandAggregateSnapshot snapshot =
        new IslandAggregateSnapshot(
            ISLAND_ID,
            PlayerId.of(PLAYER_UUID),
            IslandLifecycleState.ACTIVE,
            java.util.Optional.of(slot),
            64,
            384,
            2,
            java.util.Optional.empty(),
            now,
            now);
    return new CreateIslandResult(snapshot, replay);
  }

  private static Set<String> allPlayerPermissions() {
    return Set.of(
        OpenOneBlockPermissions.COMMAND,
        OpenOneBlockPermissions.CREATE,
        OpenOneBlockPermissions.HELP,
        OpenOneBlockPermissions.HOME,
        OpenOneBlockPermissions.INFO,
        OpenOneBlockPermissions.DELETE);
  }

  private static Player player(Set<String> permissions) {
    return proxy(
        Player.class,
        (proxy, method, arguments) ->
            switch (method.getName()) {
              case "getUniqueId" -> PLAYER_UUID;
              case "hasPermission" -> permissions.contains(arguments[0]);
              default -> defaultValue(proxy, method, arguments);
            });
  }

  private static CommandSender sender(Set<String> permissions) {
    return proxy(
        CommandSender.class,
        (proxy, method, arguments) ->
            method.getName().equals("hasPermission")
                ? permissions.contains(arguments[0])
                : defaultValue(proxy, method, arguments));
  }

  private static CommandSourceStack source(CommandSender sender) {
    return proxy(
        CommandSourceStack.class,
        (proxy, method, arguments) ->
            method.getName().equals("getSender") ? sender : defaultValue(proxy, method, arguments));
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
    return switch (method.getName()) {
      case "toString" -> "test-" + proxy.getClass().getInterfaces()[0].getSimpleName();
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      default -> primitiveDefault(method.getReturnType());
    };
  }

  private static Object primitiveDefault(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }

  private record Message(String key, Map<String, ?> placeholders) {}

  private static final class RecordingMessenger implements CommandMessenger {
    private final List<Message> entries = new ArrayList<>();

    @Override
    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
      entries.add(new Message(key, Map.copyOf(placeholders)));
    }

    List<Message> entries() {
      return List.copyOf(entries);
    }

    List<String> keys() {
      return entries.stream().map(Message::key).toList();
    }
  }
}
