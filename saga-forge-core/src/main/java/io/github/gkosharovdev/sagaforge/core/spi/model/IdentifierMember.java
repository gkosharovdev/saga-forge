package io.github.gkosharovdev.sagaforge.core.spi.model;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A member that identifies an aggregate, or links an event or command to the aggregate it concerns:
 * a field or a no-argument accessor, with the marker that made it one.
 *
 * <p>A link may name its aggregate ({@code @DomainEvent(aggregate = Order.class)}, jMolecules
 * {@code Association<Order, OrderId>}); one that does not is matched to the aggregate whose
 * identifier has the same {@link #valueType()}. A member may hold a wrapper around the identity
 * (jMolecules {@code Association}); {@link #unwrappedBy} says how to reach the identity inside.
 *
 * <p>Instances are immutable and thread-safe. Equality is by member and marker.
 */
public final class IdentifierMember {

  private final Member member;
  private final Marker marker;
  private final Class<?> valueType;
  private final Class<?> namedAggregate;
  private final Function<Object, ?> unwrap;

  private IdentifierMember(
      Member member,
      Marker marker,
      Class<?> valueType,
      Class<?> namedAggregate,
      Function<Object, ?> unwrap) {
    this.member = member;
    this.marker = marker;
    this.valueType = valueType;
    this.namedAggregate = namedAggregate;
    this.unwrap = unwrap;
  }

  /**
   * A field member, made accessible now.
   *
   * @param field an instance field
   * @param marker the marker that makes it an identifier or link
   * @return the member, naming no aggregate and holding the identity itself
   * @throws IllegalArgumentException if an argument is null or the field is static
   * @throws SecurityException if the field cannot be made accessible
   */
  public static IdentifierMember ofField(Field field, Marker marker) {
    requireNonNull(field, "field");
    requireNonNull(marker, "marker");
    if (Modifier.isStatic(field.getModifiers())) {
      throw new IllegalArgumentException("An identifier field must not be static: " + field);
    }
    return new IdentifierMember(accessible(field), marker, field.getType(), null, null);
  }

  /**
   * An accessor member, made accessible now.
   *
   * @param accessor an instance method taking no parameters and returning a value
   * @param marker the marker that makes it an identifier or link
   * @return the member, naming no aggregate and holding the identity itself
   * @throws IllegalArgumentException if an argument is null, or the method is static, takes
   *     parameters or returns {@code void}
   * @throws SecurityException if the method cannot be made accessible
   */
  public static IdentifierMember ofAccessor(Method accessor, Marker marker) {
    requireNonNull(accessor, "accessor");
    requireNonNull(marker, "marker");
    if (Modifier.isStatic(accessor.getModifiers())
        || accessor.getParameterCount() != 0
        || accessor.getReturnType() == void.class) {
      throw new IllegalArgumentException(
          "An identifier accessor must be an instance method with no parameters that returns a"
              + " value: "
              + accessor);
    }
    return new IdentifierMember(accessible(accessor), marker, accessor.getReturnType(), null, null);
  }

  /**
   * This member, naming the aggregate it concerns.
   *
   * @param aggregate the aggregate type the member names
   * @return a copy naming {@code aggregate}
   * @throws IllegalArgumentException if {@code aggregate} is null
   */
  public IdentifierMember naming(Class<?> aggregate) {
    requireNonNull(aggregate, "aggregate");
    return new IdentifierMember(member, marker, valueType, aggregate, unwrap);
  }

  /**
   * This member, holding a wrapper whose identity {@code unwrapper} returns (jMolecules {@code
   * Association::getId}).
   *
   * @param unwrapper returns the identity held by the member's non-null value
   * @param identityType the type of the identity {@code unwrapper} returns
   * @return a copy reading through {@code unwrapper}, with {@link #valueType()} {@code
   *     identityType}
   * @throws IllegalArgumentException if an argument is null
   */
  public IdentifierMember unwrappedBy(Function<Object, ?> unwrapper, Class<?> identityType) {
    requireNonNull(unwrapper, "unwrapper");
    requireNonNull(identityType, "identityType");
    return new IdentifierMember(member, marker, identityType, namedAggregate, unwrapper);
  }

  /**
   * The marker that made this member an identifier or link.
   *
   * @return the marker
   */
  public Marker marker() {
    return marker;
  }

  /**
   * The field or accessor.
   *
   * @return the {@link Field} or {@link Method}
   */
  public Member member() {
    return member;
  }

  /**
   * The member's name, as error texts use it.
   *
   * @return the field name, or the accessor name followed by {@code ()}
   */
  public String name() {
    return member instanceof Method ? member.getName() + "()" : member.getName();
  }

  /**
   * The type of the identity {@link #read} returns.
   *
   * @return the member's type, or the identity type given to {@link #unwrappedBy}
   */
  public Class<?> valueType() {
    return valueType;
  }

  /**
   * The aggregate this member names, if it names one.
   *
   * @return the named aggregate, or empty when the aggregate follows from {@link #valueType()}
   */
  public Optional<Class<?>> namedAggregate() {
    return Optional.ofNullable(namedAggregate);
  }

  /**
   * Reads the identity from {@code instance}, without copying or converting it.
   *
   * @param instance an instance of the member's declaring type
   * @return the identity, never null
   * @throws IllegalStateException if the identity is null, or reading it fails (the accessor or the
   *     unwrapper throws); the message names the member and the instance's type
   */
  public Object read(Object instance) {
    requireNonNull(instance, "instance");
    Object value = memberValue(instance);
    Object identity = value == null || unwrap == null ? value : unwrapped(value, instance);
    if (identity == null) {
      throw new IllegalStateException(
          marker.display() + " " + name() + " of " + instance.getClass().getName() + " is null");
    }
    return identity;
  }

  private Object memberValue(Object instance) {
    try {
      return member instanceof Field field
          ? field.get(instance)
          : ((Method) member).invoke(instance);
    } catch (InvocationTargetException e) {
      throw readFailure(instance, e.getCause());
    } catch (IllegalAccessException | IllegalArgumentException e) {
      throw readFailure(instance, e);
    }
  }

  private Object unwrapped(Object value, Object instance) {
    try {
      return unwrap.apply(value);
    } catch (RuntimeException e) {
      throw readFailure(instance, e);
    }
  }

  private IllegalStateException readFailure(Object instance, Throwable cause) {
    return new IllegalStateException(
        "Cannot read " + marker.display() + " " + name() + " of " + instance.getClass().getName(),
        cause);
  }

  private static <T extends AccessibleObject> T accessible(T member) {
    member.setAccessible(true);
    return member;
  }

  private static void requireNonNull(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof IdentifierMember that
        && member.equals(that.member)
        && marker.equals(that.marker);
  }

  @Override
  public int hashCode() {
    return Objects.hash(member, marker);
  }

  @Override
  public String toString() {
    return marker.display()
        + " "
        + member.getDeclaringClass().getSimpleName()
        + "."
        + name()
        + (namedAggregate == null ? "" : " -> " + namedAggregate.getSimpleName());
  }
}
