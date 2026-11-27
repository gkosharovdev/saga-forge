package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefinitionsTest {

  @Test
  @DisplayName("an index definition loses its name and table, keeps uniqueness and predicate")
  void index() {
    assertThat(
            Definitions.index(
                "CREATE INDEX saga_message_store_consumed ON public.saga_message_store USING btree"
                    + " (consumed_at) WHERE (status = ANY (ARRAY['CONSUMED'::text,"
                    + " 'CANCELLED'::text]))"))
        .isEqualTo(
            "USING btree (consumed_at) WHERE (status = ANY (ARRAY['CONSUMED'::text,"
                + " 'CANCELLED'::text]))");
    assertThat(
            Definitions.index(
                "CREATE UNIQUE INDEX any_name ON other_schema.saga_association USING btree"
                    + " (saga_type,  property)"))
        .isEqualTo("UNIQUE USING btree (saga_type, property)");
  }

  @Test
  @DisplayName("a check definition collapses whitespace and drops public. qualifiers")
  void check() {
    assertThat(Definitions.check("CHECK  ((id =\n 1))")).isEqualTo("CHECK ((id = 1))");
    assertThat(Definitions.check("CHECK ((x)::public.t IS NOT NULL)"))
        .isEqualTo("CHECK ((x)::t IS NOT NULL)");
  }
}
