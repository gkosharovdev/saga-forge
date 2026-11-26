package io.github.gkosharovdev.sagaforge.core.operations;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Where a listing continues: after the instance last modified at {@link #lastModifiedAt()} with id
 * {@link #sagaId()}, in the order ({@code lastModifiedAt}, {@code sagaId}) ascending. This is
 * keyset paging: an instance that does not change while a caller pages appears exactly once; one
 * that changes moves past the cursor and may appear again on a later page.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code
 * null} or {@code sagaId} is blank.
 *
 * @param lastModifiedAt the last-modified time of the last instance of the previous page
 * @param sagaId the id of that instance
 */
public record PageCursor(Instant lastModifiedAt, String sagaId) {

  public PageCursor {
    Checks.nonNull(lastModifiedAt, "lastModifiedAt");
    Checks.nonBlank(sagaId, "sagaId", Integer.MAX_VALUE);
  }

  /**
   * @return an opaque, URL-safe text form of this cursor, read back by {@link #fromToken(String)}
   */
  public String token() {
    String plain = lastModifiedAt.getEpochSecond() + "." + lastModifiedAt.getNano() + ":" + sagaId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads a cursor written by {@link #token()}.
   *
   * @throws IllegalArgumentException if {@code token} is not one
   */
  public static PageCursor fromToken(String token) {
    Checks.nonBlank(token, "token", Integer.MAX_VALUE);
    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("not a page cursor token: " + token, notBase64);
    }
    int colon = plain.indexOf(':');
    int dot = plain.indexOf('.');
    if (colon < 0 || dot < 0 || dot > colon) {
      throw new IllegalArgumentException("not a page cursor token: " + token);
    }
    try {
      long seconds = Long.parseLong(plain.substring(0, dot));
      int nanos = Integer.parseInt(plain.substring(dot + 1, colon));
      return new PageCursor(Instant.ofEpochSecond(seconds, nanos), plain.substring(colon + 1));
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("not a page cursor token: " + token, malformed);
    }
  }
}
