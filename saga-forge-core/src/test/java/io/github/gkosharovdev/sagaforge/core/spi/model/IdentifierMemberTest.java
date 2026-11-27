package io.github.gkosharovdev.sagaforge.core.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentifierMemberTest {

  private static final Marker MARKER = new Marker("Test", "@TestId");

  record OrderPlaced(String orderId) {}

  record Wrapper(String value) {}

  record Linked(Wrapper link) {}

  static final class Holder {
    private final String id;
    private static final String CONSTANT = "c";

    Holder(String id) {
      this.id = id;
    }

    String id() {
      return id;
    }

    String failing() {
      throw new IllegalStateException("boom");
    }

    void nothing() {}

    String withParameter(String value) {
      return value;
    }

    static String staticAccessor() {
      return CONSTANT;
    }
  }

  @Test
  @DisplayName("a record component's field reads its value, named by the field")
  void recordComponentField() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofField(OrderPlaced.class.getDeclaredField("orderId"), MARKER);

    assertThat(member.read(new OrderPlaced("o-1"))).isEqualTo("o-1");
    assertThat(member.name()).isEqualTo("orderId");
    assertThat(member.valueType()).isEqualTo(String.class);
    assertThat(member.namedAggregate()).isEmpty();
    assertThat(member.marker()).isEqualTo(MARKER);
  }

  @Test
  @DisplayName("a private field is made accessible once and read")
  void privateField() throws Exception {
    IdentifierMember member = IdentifierMember.ofField(Holder.class.getDeclaredField("id"), MARKER);

    assertThat(member.read(new Holder("h-1"))).isEqualTo("h-1");
  }

  @Test
  @DisplayName("an accessor reads through the method, named with parentheses")
  void accessor() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofAccessor(Holder.class.getDeclaredMethod("id"), MARKER);

    assertThat(member.read(new Holder("h-2"))).isEqualTo("h-2");
    assertThat(member.name()).isEqualTo("id()");
  }

  @Test
  @DisplayName("an unwrapped member reads the identity inside the wrapper and reports its type")
  void unwrapped() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofField(Linked.class.getDeclaredField("link"), MARKER)
            .unwrappedBy(wrapper -> ((Wrapper) wrapper).value(), String.class)
            .naming(Holder.class);

    assertThat(member.read(new Linked(new Wrapper("w-1")))).isEqualTo("w-1");
    assertThat(member.valueType()).isEqualTo(String.class);
    assertThat(member.namedAggregate()).contains(Holder.class);
  }

  @Test
  @DisplayName("a null identity is reported with the marker, member and type")
  void nullValue() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofField(OrderPlaced.class.getDeclaredField("orderId"), MARKER);

    assertThatThrownBy(() -> member.read(new OrderPlaced(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("@TestId orderId of " + OrderPlaced.class.getName() + " is null");
  }

  @Test
  @DisplayName("a null wrapper and a null unwrapped identity are both reported as null")
  void nullWrapper() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofField(Linked.class.getDeclaredField("link"), MARKER)
            .unwrappedBy(wrapper -> ((Wrapper) wrapper).value(), String.class);

    assertThatThrownBy(() -> member.read(new Linked(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(" is null");
    assertThatThrownBy(() -> member.read(new Linked(new Wrapper(null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(" is null");
  }

  @Test
  @DisplayName("a throwing accessor is reported naming the member, with its exception as cause")
  void throwingAccessor() throws Exception {
    IdentifierMember member =
        IdentifierMember.ofAccessor(Holder.class.getDeclaredMethod("failing"), MARKER);

    assertThatThrownBy(() -> member.read(new Holder("h")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cannot read @TestId failing() of " + Holder.class.getName())
        .hasRootCauseMessage("boom");
  }

  @Test
  @DisplayName("an accessor must take no parameters, return a value and not be static")
  void accessorShape() throws Exception {
    for (Method method :
        new Method[] {
          Holder.class.getDeclaredMethod("nothing"),
          Holder.class.getDeclaredMethod("withParameter", String.class),
          Holder.class.getDeclaredMethod("staticAccessor")
        }) {
      assertThatThrownBy(() -> IdentifierMember.ofAccessor(method, MARKER))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Field constant = Holder.class.getDeclaredField("CONSTANT");
    assertThatThrownBy(() -> IdentifierMember.ofField(constant, MARKER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("members are equal by member and marker")
  void equality() throws Exception {
    Field field = OrderPlaced.class.getDeclaredField("orderId");

    assertThat(IdentifierMember.ofField(field, MARKER))
        .isEqualTo(IdentifierMember.ofField(field, MARKER))
        .hasSameHashCodeAs(IdentifierMember.ofField(field, MARKER))
        .isNotEqualTo(IdentifierMember.ofField(field, new Marker("Other", "@Other")));
  }
}
