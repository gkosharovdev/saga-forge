package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises the definitions Postgres prints for check constraints and indexes, so that equal
 * objects compare equal whatever they are named and whichever schema holds them.
 */
final class Definitions {

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern INDEX_HEAD =
      Pattern.compile("^CREATE (UNIQUE )?INDEX \\S+ ON (ONLY )?\\S+ ", Pattern.CASE_INSENSITIVE);
  private static final Pattern PUBLIC_QUALIFIER = Pattern.compile("\\bpublic\\.");

  private Definitions() {}

  /**
   * @param definition {@code pg_get_constraintdef} of a check constraint
   * @return the definition with whitespace collapsed and {@code public.} qualifiers removed
   */
  static String check(String definition) {
    return collapse(PUBLIC_QUALIFIER.matcher(definition).replaceAll(""));
  }

  /**
   * @param definition {@code pg_get_indexdef} of an index
   * @return the definition without {@code CREATE INDEX <name> ON <table>}: {@code [UNIQUE ]USING
   *     btree (...) [WHERE ...]}, whitespace collapsed
   */
  static String index(String definition) {
    String collapsed = collapse(definition);
    Matcher head = INDEX_HEAD.matcher(collapsed);
    if (!head.find()) {
      throw new IllegalArgumentException("not an index definition: " + definition);
    }
    String unique = head.group(1) == null ? "" : "UNIQUE ";
    return unique + PUBLIC_QUALIFIER.matcher(collapsed.substring(head.end())).replaceAll("");
  }

  private static String collapse(String text) {
    return WHITESPACE.matcher(text.strip()).replaceAll(" ");
  }
}
