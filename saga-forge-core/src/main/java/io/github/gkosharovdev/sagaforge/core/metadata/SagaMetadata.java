package io.github.gkosharovdev.sagaforge.core.metadata;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Process metadata: an immutable, bounded map of application-chosen string keys to string values
 * ({@code specs/019-process-metadata}), such as a correlation id, a tenant or a request id.
 *
 * <p>An instance's metadata is captured when it starts and is fixed for its life. It travels on
 * every message the instance produces, is recorded on its lifecycle history and is restored around
 * the code the instance runs. A saga reads it through an optional field of this type, a command
 * handler through a parameter of this type. Saga Forge defines no key and never interprets a value:
 * metadata never decides routing, association or deduplication.
 *
 * <p>Keys are non-blank and at most {@value #MAX_KEY_LENGTH} characters, values non-null and at
 * most {@value #MAX_VALUE_LENGTH} characters, and there are at most {@value #MAX_ENTRIES} entries.
 * Entries are kept in key order. {@link #of(Map)} rejects a map that breaks a rule; {@link
 * #sanitized(Map, Consumer)} drops the offending entries instead.
 *
 * <p>The canonical constructor applies the rules of {@link #of(Map)} and throws {@link
 * IllegalArgumentException} naming the offending key.
 *
 * @param entries the entries, in key order; unmodifiable
 */
public record SagaMetadata(Map<String, String> entries) {

  /** The most entries a metadata value holds. */
  public static final int MAX_ENTRIES = 32;

  /** The longest key, in characters. */
  public static final int MAX_KEY_LENGTH = 128;

  /** The longest value, in characters. */
  public static final int MAX_VALUE_LENGTH = 1024;

  private static final SagaMetadata EMPTY = new SagaMetadata(Map.of());

  /** Checks every rule and keeps an unmodifiable, key-ordered copy. */
  public SagaMetadata {
    if (entries == null) {
      throw new IllegalArgumentException("entries must not be null");
    }
    if (entries.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException(
          "metadata holds at most " + MAX_ENTRIES + " entries, but has " + entries.size());
    }
    SortedMap<String, String> copy = new TreeMap<>();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      String problem = problem(entry.getKey(), entry.getValue());
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    entries = Collections.unmodifiableSortedMap(copy);
  }

  /**
   * @return metadata without entries
   */
  public static SagaMetadata empty() {
    return EMPTY;
  }

  /**
   * Metadata holding exactly {@code entries}.
   *
   * @param entries the entries; must not be {@code null}
   * @return the metadata
   * @throws IllegalArgumentException if {@code entries} is {@code null}, holds a blank or {@code
   *     null} key, a {@code null} value, a key or value over its length bound, or more than {@value
   *     #MAX_ENTRIES} entries
   */
  public static SagaMetadata of(Map<String, String> entries) {
    if (entries == null) {
      throw new IllegalArgumentException("entries must not be null");
    }
    return entries.isEmpty() ? EMPTY : new SagaMetadata(entries);
  }

  /**
   * Metadata holding the valid entries of {@code entries}; never fails. An entry with a blank or
   * {@code null} key, a {@code null} value or a key or value over its length bound is dropped, and
   * so are the valid entries beyond the first {@value #MAX_ENTRIES} in key order. {@code
   * droppedKey} is called once for each dropped entry with its key ({@code ""} for a {@code null}
   * key), never with its value.
   *
   * @param entries the captured entries; {@code null} gives empty metadata
   * @param droppedKey told the key of each dropped entry; must not be {@code null}
   * @return the metadata
   * @throws IllegalArgumentException if {@code droppedKey} is {@code null}
   */
  public static SagaMetadata sanitized(Map<String, String> entries, Consumer<String> droppedKey) {
    if (droppedKey == null) {
      throw new IllegalArgumentException("droppedKey must not be null");
    }
    if (entries == null || entries.isEmpty()) {
      return EMPTY;
    }
    SortedMap<String, String> valid = new TreeMap<>();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      if (problem(entry.getKey(), entry.getValue()) == null) {
        valid.put(entry.getKey(), entry.getValue());
      } else {
        droppedKey.accept(entry.getKey() == null ? "" : entry.getKey());
      }
    }
    SortedMap<String, String> kept = new TreeMap<>();
    for (Map.Entry<String, String> entry : valid.entrySet()) {
      if (kept.size() < MAX_ENTRIES) {
        kept.put(entry.getKey(), entry.getValue());
      } else {
        droppedKey.accept(entry.getKey());
      }
    }
    return kept.isEmpty() ? EMPTY : new SagaMetadata(kept);
  }

  /**
   * @param key the key; must not be {@code null}
   * @return the value of {@code key}, if present
   * @throws IllegalArgumentException if {@code key} is {@code null}
   */
  public Optional<String> get(String key) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    return Optional.ofNullable(entries.get(key));
  }

  /**
   * @return whether there is no entry
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * @return the entries, in key order; unmodifiable
   */
  public Map<String, String> asMap() {
    return entries;
  }

  private static String problem(String key, String value) {
    if (key == null || key.isBlank()) {
      return "metadata keys must not be null or blank, but got '" + key + "'";
    }
    if (key.length() > MAX_KEY_LENGTH) {
      return "metadata key '"
          + key.substring(0, 32)
          + "...' is longer than "
          + MAX_KEY_LENGTH
          + " characters";
    }
    if (value == null) {
      return "metadata key '" + key + "' has a null value";
    }
    if (value.length() > MAX_VALUE_LENGTH) {
      return "the value of metadata key '"
          + key
          + "' is longer than "
          + MAX_VALUE_LENGTH
          + " characters";
    }
    return null;
  }
}
