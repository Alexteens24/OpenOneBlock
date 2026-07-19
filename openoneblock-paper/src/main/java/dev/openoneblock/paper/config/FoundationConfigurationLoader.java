package dev.openoneblock.paper.config;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.BuildHeight;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.DatabaseSettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.DefaultProgression;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.ExecutorSettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.MagicBlockSettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.MessageSettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.ObservabilitySettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.OperationSettings;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.RoleSettings;
import dev.openoneblock.paper.world.SharedWorldSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.World;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Loads all foundation YAML files into one fully validated immutable candidate. */
public final class FoundationConfigurationLoader {
  private static final int SCHEMA_VERSION = 1;

  /** Creates a stateless strict foundation configuration loader. */
  public FoundationConfigurationLoader() {}

  /**
   * Parses and validates the complete managed configuration directory.
   *
   * @param dataDirectory plugin data directory
   * @return immutable candidate and deterministic fingerprint
   * @throws ConfigurationValidationException when any file is missing, malformed, or unsafe
   */
  public FoundationConfigurationSnapshot load(Path dataDirectory)
      throws ConfigurationValidationException {
    List<ConfigurationProblem> problems = new ArrayList<>();
    StrictConfigNode main = read(dataDirectory, "config.yml", problems);
    StrictConfigNode worldsRoot = read(dataDirectory, "worlds.yml", problems);
    StrictConfigNode islands = read(dataDirectory, "islands.yml", problems);
    StrictConfigNode rolesRoot = read(dataDirectory, "roles.yml", problems);
    StrictConfigNode messagesRoot = read(dataDirectory, "messages.yml", problems);

    validateSchema(main, "config.yml");
    validateSchema(worldsRoot, "worlds.yml");
    validateSchema(islands, "islands.yml");
    validateSchema(rolesRoot, "roles.yml");
    validateSchema(messagesRoot, "messages.yml");

    DatabaseSettings database = parseDatabase(main, problems);
    ExecutorSettings executors = parseExecutors(main, problems);
    ObservabilitySettings observability = parseObservability(main);
    WorldCandidate worldCandidate = parseWorlds(worldsRoot, problems);
    IslandCandidate islandCandidate = parseIslands(islands, problems);
    Map<String, RoleSettings> roles = parseRoles(rolesRoot, problems);
    MessageSettings messages = parseMessages(messagesRoot);

    if (!problems.isEmpty()) {
      throw new ConfigurationValidationException(problems);
    }

    String fingerprint =
        fingerprint(
            database, executors, observability, worldCandidate, islandCandidate, roles, messages);
    return new FoundationConfigurationSnapshot(
        database,
        executors,
        observability,
        worldCandidate.grid(),
        worldCandidate.height(),
        worldCandidate.worlds(),
        islandCandidate.operations(),
        islandCandidate.magicBlock(),
        islandCandidate.defaults(),
        roles,
        messages,
        fingerprint);
  }

  private static DatabaseSettings parseDatabase(
      StrictConfigNode main, List<ConfigurationProblem> problems) {
    main.rejectUnknown("schema-version", "database", "executors", "observability");
    StrictConfigNode database = main.mapping("database");
    database.rejectUnknown("type", "file", "busy-timeout-ms", "retry", "write-queue");
    String type = database.string("type").toLowerCase(Locale.ROOT);
    String file = database.string("file");
    int busyTimeout = database.integer("busy-timeout-ms");
    StrictConfigNode retry = database.mapping("retry");
    retry.rejectUnknown("maximum-attempts", "minimum-backoff-ms", "maximum-backoff-ms");
    int attempts = retry.integer("maximum-attempts");
    long minimumBackoff = retry.longValue("minimum-backoff-ms");
    long maximumBackoff = retry.longValue("maximum-backoff-ms");
    StrictConfigNode queue = database.mapping("write-queue");
    queue.rejectUnknown("capacity", "batch-size");
    int capacity = queue.integer("capacity");
    int batchSize = queue.integer("batch-size");

    if (!type.equals("sqlite")) {
      database.constraint("type", "sqlite", type, "use sqlite until MySQL support is implemented");
    }
    try {
      Path configuredPath = file.isEmpty() ? Path.of(".") : Path.of(file).normalize();
      if (configuredPath.isAbsolute() || configuredPath.startsWith("..")) {
        database.constraint(
            "file",
            "safe relative path",
            file,
            "place the database inside the plugin data directory");
      }
    } catch (InvalidPathException exception) {
      database.constraint(
          "file", "valid relative path", file, "remove unsupported filesystem characters");
    }
    positive(database, "busy-timeout-ms", busyTimeout, false);
    positive(retry, "maximum-attempts", attempts, true);
    positive(retry, "minimum-backoff-ms", minimumBackoff, false);
    if (maximumBackoff < minimumBackoff) {
      retry.constraint(
          "maximum-backoff-ms",
          "integer >= minimum-backoff-ms",
          maximumBackoff,
          "increase the maximum retry backoff");
    }
    positive(queue, "capacity", capacity, true);
    positive(queue, "batch-size", batchSize, true);
    if (batchSize > capacity) {
      queue.constraint(
          "batch-size", "integer <= capacity", batchSize, "reduce the write batch size");
    }
    return new DatabaseSettings(
        type, file, busyTimeout, attempts, minimumBackoff, maximumBackoff, capacity, batchSize);
  }

  private static ExecutorSettings parseExecutors(
      StrictConfigNode main, List<ConfigurationProblem> problems) {
    StrictConfigNode executors = main.mapping("executors");
    executors.rejectUnknown("sql-threads", "computation-threads", "queue-capacity");
    int sqlThreads = executors.integer("sql-threads");
    int computationThreads = executors.integer("computation-threads");
    int queueCapacity = executors.integer("queue-capacity");
    positive(executors, "sql-threads", sqlThreads, true);
    positive(executors, "computation-threads", computationThreads, true);
    positive(executors, "queue-capacity", queueCapacity, true);
    return new ExecutorSettings(sqlThreads, computationThreads, queueCapacity);
  }

  private static ObservabilitySettings parseObservability(StrictConfigNode main) {
    StrictConfigNode node = main.mapping("observability");
    node.rejectUnknown("debug", "audit", "metrics");
    return new ObservabilitySettings(node.bool("debug"), node.bool("audit"), node.bool("metrics"));
  }

  private static WorldCandidate parseWorlds(
      StrictConfigNode root, List<ConfigurationProblem> problems) {
    root.rejectUnknown("schema-version", "grid", "build-height", "shards");
    StrictConfigNode gridNode = root.mapping("grid");
    gridNode.rejectUnknown("cell-size", "initial-border", "maximum-border", "safety-gap");
    int cellSize = gridNode.integer("cell-size");
    int initialBorder = gridNode.integer("initial-border");
    int maximumBorder = gridNode.integer("maximum-border");
    int safetyGap = gridNode.integer("safety-gap");
    GridConfiguration grid;
    try {
      grid = new GridConfiguration(cellSize, initialBorder, maximumBorder, safetyGap);
    } catch (IllegalArgumentException exception) {
      gridNode.constraint(
          "cell-size",
          "valid grid geometry",
          cellSize + "/" + initialBorder + "/" + maximumBorder + "/" + safetyGap,
          exception.getMessage());
      grid = GridConfiguration.DEFAULT;
    }

    StrictConfigNode heightNode = root.mapping("build-height");
    heightNode.rejectUnknown("minimum-y", "maximum-y-exclusive");
    int minimumY = heightNode.integer("minimum-y");
    int maximumY = heightNode.integer("maximum-y-exclusive");
    if (minimumY >= maximumY) {
      heightNode.constraint(
          "maximum-y-exclusive",
          "integer > minimum-y",
          maximumY,
          "increase the maximum build height");
    }

    List<SharedWorldSpec> worlds = new ArrayList<>();
    Set<String> worldNames = new LinkedHashSet<>();
    Set<String> projections = new LinkedHashSet<>();
    for (StrictConfigNode shard : root.mappingList("shards")) {
      shard.rejectUnknown("id", "dimensions");
      ShardGroupId shardId = parseShardId(shard, problems);
      for (StrictConfigNode dimension : shard.mappingList("dimensions")) {
        dimension.rejectUnknown("id", "world-name", "environment", "seed");
        DimensionId dimensionId = parseDimensionId(dimension, problems);
        String worldName = dimension.string("world-name");
        World.Environment environment = parseEnvironment(dimension, problems);
        long seed = dimension.longValue("seed");
        String projectionKey = shardId + "/" + dimensionId;
        if (!worldNames.add(worldName)) {
          dimension.constraint(
              "world-name", "globally unique world name", worldName, "rename this shared world");
        }
        if (!projections.add(projectionKey)) {
          dimension.constraint(
              "id",
              "unique dimension in its shard",
              dimensionId,
              "remove the duplicate shard/dimension mapping");
        }
        try {
          worlds.add(new SharedWorldSpec(worldName, shardId, dimensionId, environment, seed));
        } catch (IllegalArgumentException exception) {
          dimension.constraint(
              "world-name", "safe lowercase world name", worldName, exception.getMessage());
        }
      }
    }
    if (worlds.isEmpty()) {
      root.constraint("shards", "at least one valid world projection", worlds, "configure a shard");
    }
    return new WorldCandidate(grid, new BuildHeight(minimumY, maximumY), List.copyOf(worlds));
  }

  private static IslandCandidate parseIslands(
      StrictConfigNode root, List<ConfigurationProblem> problems) {
    root.rejectUnknown("schema-version", "operations", "magic-block", "defaults");
    StrictConfigNode operations = root.mapping("operations");
    operations.rejectUnknown(
        "creation-timeout-seconds",
        "reset-timeout-seconds",
        "delete-timeout-seconds",
        "quarantine-cleanup-failures");
    int creation = operations.integer("creation-timeout-seconds");
    int reset = operations.integer("reset-timeout-seconds");
    int delete = operations.integer("delete-timeout-seconds");
    int cleanupFailures = operations.integer("quarantine-cleanup-failures");
    positive(operations, "creation-timeout-seconds", creation, true);
    positive(operations, "reset-timeout-seconds", reset, true);
    positive(operations, "delete-timeout-seconds", delete, true);
    positive(operations, "quarantine-cleanup-failures", cleanupFailures, true);

    StrictConfigNode magic = root.mapping("magic-block");
    magic.rejectUnknown("starter-material", "regeneration-delay-ticks");
    String starterMaterial = magic.string("starter-material");
    long regenerationDelay = magic.longValue("regeneration-delay-ticks");
    if (!starterMaterial.matches("[A-Z0-9_]+")) {
      magic.constraint(
          "starter-material",
          "uppercase material identifier",
          starterMaterial,
          "use a Vanilla material name such as GRASS_BLOCK");
    }
    positive(magic, "regeneration-delay-ticks", regenerationDelay, false);

    StrictConfigNode defaults = root.mapping("defaults");
    defaults.rejectUnknown("phase-id", "profile-id");
    NamespacedId phaseId = parseNamespaced(defaults, "phase-id", problems);
    NamespacedId profileId = parseNamespaced(defaults, "profile-id", problems);
    return new IslandCandidate(
        new OperationSettings(creation, reset, delete, cleanupFailures),
        new MagicBlockSettings(starterMaterial, regenerationDelay),
        new DefaultProgression(phaseId, profileId));
  }

  private static Map<String, RoleSettings> parseRoles(
      StrictConfigNode root, List<ConfigurationProblem> problems) {
    root.rejectUnknown("schema-version", "roles");
    Map<String, RoleSettings> roles = new LinkedHashMap<>();
    Map<String, StrictConfigNode> nodes = root.namedMappings("roles");
    for (Map.Entry<String, StrictConfigNode> entry : nodes.entrySet()) {
      String role = entry.getKey();
      StrictConfigNode node = entry.getValue();
      node.rejectUnknown("inherits", "permissions");
      if (!role.matches("[a-z][a-z0-9_-]*")) {
        node.constraint("inherits", "safe lowercase role key", role, "rename this role");
      }
      roles.put(
          role,
          new RoleSettings(
              node.stringList("inherits"), new LinkedHashSet<>(node.stringList("permissions"))));
    }
    for (Map.Entry<String, RoleSettings> entry : roles.entrySet()) {
      for (String inherited : entry.getValue().inherits()) {
        if (!roles.containsKey(inherited)) {
          nodes
              .get(entry.getKey())
              .constraint(
                  "inherits",
                  "existing role name",
                  inherited,
                  "define the role or remove the inheritance reference");
        }
      }
    }
    detectRoleCycles(roles, nodes, problems);
    return Map.copyOf(roles);
  }

  private static MessageSettings parseMessages(StrictConfigNode root) {
    root.rejectUnknown("schema-version", "locale", "formatting", "messages");
    String locale = root.string("locale");
    StrictConfigNode formatting = root.mapping("formatting");
    formatting.rejectUnknown("mini-message", "legacy-colors");
    return new MessageSettings(
        locale,
        formatting.bool("mini-message"),
        formatting.bool("legacy-colors"),
        root.stringMapping("messages"));
  }

  private static StrictConfigNode read(
      Path directory, String file, List<ConfigurationProblem> problems) {
    Path path = directory.resolve(file);
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setAllowRecursiveKeys(false);
    options.setMaxAliasesForCollections(50);
    options.setCodePointLimit(3_000_000);
    try (InputStream input = Files.newInputStream(path)) {
      Object loaded = new Yaml(new SafeConstructor(options)).load(input);
      if (loaded instanceof Map<?, ?> map) {
        return new StrictConfigNode(file, "", map, problems);
      }
      problems.add(
          new ConfigurationProblem(
              file,
              "<root>",
              "YAML mapping",
              loaded == null ? "empty document" : loaded.getClass().getSimpleName(),
              "replace the file with a supported configuration mapping"));
    } catch (IOException | YAMLException exception) {
      problems.add(
          new ConfigurationProblem(
              file,
              "<root>",
              "readable valid YAML",
              exception.getMessage() == null
                  ? exception.getClass().getSimpleName()
                  : exception.getMessage(),
              "restore the default file or fix the reported YAML syntax"));
    }
    return new StrictConfigNode(file, "", Map.of(), problems);
  }

  private static void validateSchema(StrictConfigNode root, String file) {
    int schemaVersion = root.integer("schema-version");
    if (schemaVersion != SCHEMA_VERSION) {
      root.constraint(
          "schema-version",
          "supported version " + SCHEMA_VERSION,
          schemaVersion,
          "run a supported config migration before starting the plugin");
    }
  }

  private static ShardGroupId parseShardId(
      StrictConfigNode node, List<ConfigurationProblem> problems) {
    String value = node.string("id");
    try {
      return ShardGroupId.parse(value);
    } catch (IllegalArgumentException exception) {
      node.constraint("id", "namespaced ID", value, exception.getMessage());
      return ShardGroupId.of("openoneblock", "invalid");
    }
  }

  private static DimensionId parseDimensionId(
      StrictConfigNode node, List<ConfigurationProblem> problems) {
    String value = node.string("id");
    try {
      return DimensionId.parse(value);
    } catch (IllegalArgumentException exception) {
      node.constraint("id", "namespaced ID", value, exception.getMessage());
      return DimensionId.of("openoneblock", "invalid");
    }
  }

  private static NamespacedId parseNamespaced(
      StrictConfigNode node, String key, List<ConfigurationProblem> problems) {
    String value = node.string(key);
    try {
      return NamespacedId.parse(value);
    } catch (IllegalArgumentException exception) {
      node.constraint(key, "namespaced ID", value, exception.getMessage());
      return NamespacedId.of("openoneblock", "invalid");
    }
  }

  private static World.Environment parseEnvironment(
      StrictConfigNode node, List<ConfigurationProblem> problems) {
    String value = node.string("environment");
    try {
      return World.Environment.valueOf(value);
    } catch (IllegalArgumentException exception) {
      node.constraint(
          "environment",
          "NORMAL, NETHER, or THE_END",
          value,
          "use a Paper World.Environment constant");
      return World.Environment.NORMAL;
    }
  }

  private static void positive(
      StrictConfigNode node, String key, long value, boolean strictlyPositive) {
    boolean valid = strictlyPositive ? value > 0 : value >= 0;
    if (!valid) {
      node.constraint(
          key,
          strictlyPositive ? "positive integer" : "non-negative integer",
          value,
          "increase this bounded operational value");
    }
  }

  private static void detectRoleCycles(
      Map<String, RoleSettings> roles,
      Map<String, StrictConfigNode> nodes,
      List<ConfigurationProblem> problems) {
    Set<String> complete = new LinkedHashSet<>();
    Set<String> visiting = new LinkedHashSet<>();
    ArrayDeque<String> path = new ArrayDeque<>();
    for (String role : roles.keySet()) {
      visitRole(role, roles, nodes, complete, visiting, path);
    }
  }

  private static void visitRole(
      String role,
      Map<String, RoleSettings> roles,
      Map<String, StrictConfigNode> nodes,
      Set<String> complete,
      Set<String> visiting,
      ArrayDeque<String> path) {
    if (complete.contains(role) || !roles.containsKey(role)) {
      return;
    }
    if (!visiting.add(role)) {
      nodes
          .get(role)
          .constraint(
              "inherits",
              "acyclic role inheritance",
              String.join(" -> ", path) + " -> " + role,
              "remove one inheritance edge in this cycle");
      return;
    }
    path.addLast(role);
    for (String inherited : roles.get(role).inherits()) {
      visitRole(inherited, roles, nodes, complete, visiting, path);
    }
    path.removeLast();
    visiting.remove(role);
    complete.add(role);
  }

  private static String fingerprint(
      DatabaseSettings database,
      ExecutorSettings executors,
      ObservabilitySettings observability,
      WorldCandidate worlds,
      IslandCandidate islands,
      Map<String, RoleSettings> roles,
      MessageSettings messages) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      List<Object> orderedSections = new ArrayList<>();
      orderedSections.add(database);
      orderedSections.add(executors);
      orderedSections.add(observability);
      orderedSections.add(worlds.grid());
      orderedSections.add(worlds.height());
      worlds.worlds().stream()
          .sorted(
              java.util.Comparator.comparing(SharedWorldSpec::worldName)
                  .thenComparing(spec -> spec.shardGroupId().toString())
                  .thenComparing(spec -> spec.dimensionId().toString()))
          .forEach(orderedSections::add);
      orderedSections.add(islands);
      for (Map.Entry<String, RoleSettings> entry : new TreeMap<>(roles).entrySet()) {
        orderedSections.add(entry.getKey());
        orderedSections.add(entry.getValue().inherits());
        orderedSections.add(entry.getValue().permissions().stream().sorted().toList());
      }
      orderedSections.add(messages.locale());
      orderedSections.add(messages.miniMessage());
      orderedSections.add(messages.legacyColors());
      orderedSections.add(new TreeMap<>(messages.messages()));
      for (Object section : orderedSections) {
        digest.update(section.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide SHA-256", exception);
    }
  }

  private record WorldCandidate(
      GridConfiguration grid, BuildHeight height, List<SharedWorldSpec> worlds) {}

  private record IslandCandidate(
      OperationSettings operations, MagicBlockSettings magicBlock, DefaultProgression defaults) {}
}
