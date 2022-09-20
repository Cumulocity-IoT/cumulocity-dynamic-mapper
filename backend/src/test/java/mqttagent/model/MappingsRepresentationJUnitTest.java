package mqttagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class MappingsRepresentationJUnitTest {

  @Test
  void testRegexpNormalizeTopic() {

    String topic1 = "/rom/hamburg/madrid/#/";
    String nt1 = topic1.replaceAll(MappingsRepresentation.REGEXP_REMOVE_TRAILING_SLASHES, "#");
    assertEquals(nt1, "/rom/hamburg/madrid/#");

    String topic2 = "////rom/hamburg/madrid/#/////";
    String nt2 = topic2.replaceAll(MappingsRepresentation.REGEXP_REDUCE_LEADING_TRAILING_SLASHES, "/");
    assertEquals(nt2, "/rom/hamburg/madrid/#/");

    String topic3 = "////rom/hamburg/madrid//+//+//";
    int count = topic3.length() - topic3.replace("+", "").length();
    System.out.println(count);

  }

  @Test
  void testNormalizeTopic() {

    String topic1 = "/rom/hamburg/madrid/#/";
    assertEquals(MappingsRepresentation.normalizeTopic(topic1), "/rom/hamburg/madrid/#");

    String topic2 = "///rom/hamburg/madrid/+//";
    assertEquals(MappingsRepresentation.normalizeTopic(topic2), "/rom/hamburg/madrid/+/");

  }

}
