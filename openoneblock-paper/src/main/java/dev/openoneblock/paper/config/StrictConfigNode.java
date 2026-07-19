package dev.openoneblock.paper.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Path-aware strict YAML mapping reader that accumulates actionable failures. */
final class StrictConfigNode {
  private final String file;
  private final String path;
  private final Map<?, ?> values;
  private final List<ConfigurationProblem> problems;

  StrictConfigNode(
      String file, String path, Map<?, ?> values, List<ConfigurationProblem> problems) {
    this.file = file;
    this.path = path;
    this.values = values;
    this.problems = problems;
    for (Object key : values.keySet()) {
      if (!(key instanceof String)) {
        problem(path, "string field name", describe(key), "quote and rename this YAML key");
      }
    }
  }

  void rejectUnknown(String... allowed) {
    Set<String> allowedKeys = Set.of(allowed);
    for (Object key : values.keySet()) {
      if (key instanceof String stringKey && !allowedKeys.contains(stringKey)) {
        problem(
            childPath(stringKey),
            "one of " + allowedKeys,
            "unknown field",
            "remove the field or migrate it to the current schema");
      }
    }
  }

  int integer(String key) {
    Object value = required(key, "integer");
    if (value instanceof Integer integer) {
      return integer;
    }
    if (value instanceof Long longValue
        && longValue >= Integer.MIN_VALUE
        && longValue <= Integer.MAX_VALUE) {
      return longValue.intValue();
    }
    typeProblem(key, "integer", value);
    return 0;
  }

  long longValue(String key) {
    Object value = required(key, "integer");
    if (value instanceof Number number && !(value instanceof Float) && !(value instanceof Double)) {
      return number.longValue();
    }
    typeProblem(key, "integer", value);
    return 0L;
  }

  boolean bool(String key) {
    Object value = required(key, "boolean");
    if (value instanceof Boolean bool) {
      return bool;
    }
    typeProblem(key, "boolean", value);
    return false;
  }

  String string(String key) {
    Object value = required(key, "string");
    if (value instanceof String string) {
      return string;
    }
    typeProblem(key, "string", value);
    return "";
  }

  StrictConfigNode mapping(String key) {
    Object value = required(key, "mapping");
    if (value instanceof Map<?, ?> map) {
      return new StrictConfigNode(file, childPath(key), map, problems);
    }
    typeProblem(key, "mapping", value);
    return new StrictConfigNode(file, childPath(key), Map.of(), problems);
  }

  List<StrictConfigNode> mappingList(String key) {
    Object value = required(key, "list of mappings");
    if (!(value instanceof List<?> list)) {
      typeProblem(key, "list of mappings", value);
      return List.of();
    }
    List<StrictConfigNode> result = new ArrayList<>();
    for (int index = 0; index < list.size(); index++) {
      Object entry = list.get(index);
      String entryPath = childPath(key) + "[" + index + "]";
      if (entry instanceof Map<?, ?> map) {
        result.add(new StrictConfigNode(file, entryPath, map, problems));
      } else {
        problem(entryPath, "mapping", describe(entry), "replace this list entry with a mapping");
      }
    }
    return List.copyOf(result);
  }

  List<String> stringList(String key) {
    Object value = required(key, "list of strings");
    if (!(value instanceof List<?> list)) {
      typeProblem(key, "list of strings", value);
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (int index = 0; index < list.size(); index++) {
      Object entry = list.get(index);
      if (entry instanceof String string) {
        result.add(string);
      } else {
        problem(
            childPath(key) + "[" + index + "]",
            "string",
            describe(entry),
            "replace this value with text");
      }
    }
    return List.copyOf(result);
  }

  Map<String, StrictConfigNode> namedMappings(String key) {
    StrictConfigNode container = mapping(key);
    Map<String, StrictConfigNode> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : container.values.entrySet()) {
      if (!(entry.getKey() instanceof String name)) {
        continue;
      }
      if (entry.getValue() instanceof Map<?, ?> map) {
        result.put(name, new StrictConfigNode(file, container.childPath(name), map, problems));
      } else {
        problem(
            container.childPath(name),
            "mapping",
            describe(entry.getValue()),
            "replace this value with a mapping");
      }
    }
    return Map.copyOf(result);
  }

  Map<String, String> stringMapping(String key) {
    StrictConfigNode container = mapping(key);
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : container.values.entrySet()) {
      if (entry.getKey() instanceof String name && entry.getValue() instanceof String text) {
        result.put(name, text);
      } else if (entry.getKey() instanceof String name) {
        problem(
            container.childPath(name),
            "string",
            describe(entry.getValue()),
            "replace this value with message text");
      }
    }
    return Map.copyOf(result);
  }

  void constraint(String key, String expected, Object actual, String remediation) {
    problem(childPath(key), expected, describe(actual), remediation);
  }

  String path() {
    return path;
  }

  private Object required(String key, String expected) {
    if (!values.containsKey(key)) {
      problem(childPath(key), expected, "missing", "add the required field");
      return null;
    }
    return values.get(key);
  }

  private void typeProblem(String key, String expected, Object value) {
    if (value != null || values.containsKey(key)) {
      problem(
          childPath(key), expected, describe(value), "replace this value with the expected type");
    }
  }

  private void problem(String problemPath, String expected, String actual, String remediation) {
    problems.add(new ConfigurationProblem(file, problemPath, expected, actual, remediation));
  }

  private String childPath(String child) {
    return path.isEmpty() ? child : path + "." + child;
  }

  private static String describe(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map<?, ?>) {
      return "mapping";
    }
    if (value instanceof List<?>) {
      return "list";
    }
    return value.getClass().getSimpleName() + "(" + value + ")";
  }
}
