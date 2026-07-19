package dev.openoneblock.paper.config;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Registered append-only migrations for managed OpenOneBlock configuration files. */
public final class BuiltInConfigurationMigrations {
  private static final Pattern ROLE_HEADER =
      Pattern.compile("(?m)^  ([a-z][a-z0-9_-]*):\\R");
  private static final Map<String, Integer> ROLE_AUTHORITY =
      Map.of(
          "owner", 1000,
          "co_owner", 800,
          "moderator", 600,
          "member", 400,
          "trusted", 200,
          "visitor", 100,
          "banned", 0);

  private BuiltInConfigurationMigrations() {}

  /** Creates the complete adjacent-edge migrator. */
  public static ConfigurationMigrator migrator() {
    return new ConfigurationMigrator(
        List.of(
            new ManagedConfigMigration("islands.yml", 1, 2, BuiltInConfigurationMigrations::islandsV2),
            new ManagedConfigMigration("roles.yml", 1, 2, BuiltInConfigurationMigrations::rolesV2)));
  }

  /** Returns current per-file targets without forcing unrelated files to share a schema number. */
  public static Map<String, Integer> targetVersions() {
    return Map.of("islands.yml", 2, "roles.yml", 2);
  }

  private static String islandsV2(String original) {
    String migrated = schemaTwo(original);
    String anchor = "magic-block:";
    int insertion = migrated.indexOf(anchor);
    if (insertion < 0) {
      throw new IllegalArgumentException("islands.yml schema 1 is missing magic-block section");
    }
    String team =
        "team:\n"
            + "  maximum-size: 8\n"
            + "  invitation-expiry-seconds: 300\n";
    return migrated.substring(0, insertion) + team + migrated.substring(insertion);
  }

  private static String rolesV2(String original) {
    String migrated = schemaTwo(original);
    Matcher matcher = ROLE_HEADER.matcher(migrated);
    StringBuffer output = new StringBuffer();
    int roles = 0;
    Set<String> existingRoles = new LinkedHashSet<>();
    while (matcher.find()) {
      String role = matcher.group(1);
      existingRoles.add(role);
      int authority = ROLE_AUTHORITY.getOrDefault(role, 400);
      matcher.appendReplacement(
          output, Matcher.quoteReplacement(matcher.group() + "    authority: " + authority + "\n"));
      roles++;
    }
    matcher.appendTail(output);
    if (roles == 0) {
      throw new IllegalArgumentException("roles.yml schema 1 contains no role definitions");
    }
    StringBuilder complete = new StringBuilder(output);
    for (String required : List.of("co_owner", "moderator", "member", "trusted", "visitor", "banned")) {
      if (!existingRoles.contains(required)) {
        if (!complete.isEmpty() && complete.charAt(complete.length() - 1) != '\n') {
          complete.append('\n');
        }
        complete.append(defaultRole(required));
      }
    }
    return complete.toString();
  }

  private static String defaultRole(String role) {
    return switch (role) {
      case "co_owner" ->
          "  co_owner:\n"
              + "    authority: 800\n"
              + "    inherits: [moderator]\n"
              + "    permissions: [UPGRADE_ISLAND, RESET_ISLAND]\n";
      case "moderator" ->
          "  moderator:\n"
              + "    authority: 600\n"
              + "    inherits: [member]\n"
              + "    permissions: [INVITE_MEMBER, KICK_MEMBER, CHANGE_SETTINGS]\n";
      case "member" ->
          "  member:\n"
              + "    authority: 400\n"
              + "    inherits: []\n"
              + "    permissions: [BLOCK_BREAK, BLOCK_PLACE, CONTAINER_OPEN, ENTITY_INTERACT]\n";
      case "trusted" ->
          "  trusted:\n"
              + "    authority: 200\n"
              + "    inherits: []\n"
              + "    permissions: [CONTAINER_OPEN, ENTITY_INTERACT, TELEPORT]\n";
      case "visitor" ->
          "  visitor:\n    authority: 100\n    inherits: []\n    permissions: []\n";
      case "banned" ->
          "  banned:\n    authority: 0\n    inherits: []\n    permissions: []\n";
      default -> throw new IllegalArgumentException("unknown built-in role " + role);
    };
  }

  private static String schemaTwo(String original) {
    String migrated = original.replaceFirst("(?m)^schema-version:\\s*1\\s*$", "schema-version: 2");
    if (migrated.equals(original)) {
      throw new IllegalArgumentException("managed schema 1 header is missing");
    }
    return migrated;
  }
}
