package mqtt.mapping.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.MQTTConfiguration;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;

@Slf4j
public class MappingsRepresentationJUnitTest {

  @Test
  void testRegexpNormalizeTopic() {

    String topic1 = "/rom/hamburg/madrid/#/";
    String nt1 = topic1.replaceAll(MappingsRepresentation.REGEXP_REMOVE_TRAILING_SLASHES, "#");
    assertEquals(nt1, "/rom/hamburg/madrid/#");

    String topic2 = "////rom/hamburg/madrid/#/////";
    String nt2 = topic2.replaceAll(MappingsRepresentation.REGEXP_REDUCE_LEADING_TRAILING_SLASHES, "/");
    assertEquals(nt2, "/rom/hamburg/madrid/#/");

  }

  @Test
  void testNormalizeTopic() {

    String topic1 = "/rom/hamburg/madrid/#/";
    assertEquals(MappingsRepresentation.normalizeTopic(topic1), "/rom/hamburg/madrid/#");

    String topic2 = "///rom/hamburg/madrid/+//";
    assertEquals(MappingsRepresentation.normalizeTopic(topic2), "/rom/hamburg/madrid/+/");

  }

  @Test
  void testIsTemplateTopicValid() {

    Mapping m1 = new Mapping();
    m1.setTemplateTopic("/device/+/east/");
    m1.setSubscriptionTopic("/device/#");
    assertEquals(new ArrayList<ValidationError>(), MappingsRepresentation.isTemplateTopicSubscriptionTopicValid(m1));

    Mapping m2 = new Mapping();
    m2.setTemplateTopic("/device");
    m2.setSubscriptionTopic("/device/#");
    ValidationError[] l2 = { ValidationError.TemplateTopic_Must_Match_The_SubscriptionTopic };
    assertEquals(Arrays.asList(l2), MappingsRepresentation.isTemplateTopicSubscriptionTopicValid(m2));

    Mapping m3 = new Mapping();
    m3.setTemplateTopic("/device/");
    m3.setSubscriptionTopic("/device/#");
    assertEquals(new ArrayList<ValidationError>(), MappingsRepresentation.isTemplateTopicSubscriptionTopicValid(m3));
  }

  @Test
  void testSubstitutionIsSorted() {

    Mapping m1 = new Mapping();
    MappingSubstitution s1 = new MappingSubstitution();
    s1.pathSource = "p1s";
    s1.pathTarget = "p1t";
    MappingSubstitution s2 = new MappingSubstitution();
    s2.pathSource = "p2s";
    s2.pathTarget = "p2t";
    s2.definesIdentifier = true;
    MappingSubstitution s3 = new MappingSubstitution();
    s3.pathSource = "p3s";
    s3.pathTarget = "p3t";
    m1.substitutions = new MappingSubstitution[] { s1, s2, s3 };

    assertEquals("p1s", m1.substitutions[0].pathSource);
    m1.sortSubstitutions();
    log.info("My substitutions {}", Arrays.toString(m1.substitutions));
    assertEquals("p2s", m1.substitutions[0].pathSource);

  }

  void testTemplateTopicMatchesTemplateTopicSample() {

    Mapping m1 = new Mapping();
    m1.templateTopic = "/plant1/+/machine1";
    m1.templateTopicSample = "/plant1/line1/machine1";
    assertEquals(0, MappingsRepresentation
        .isTemplateTopicTemplateAndTopicSampleValid(m1.templateTopic, m1.templateTopicSample).size() == 0);

    Mapping m2 = new Mapping();
    m2.templateTopic = "/plant2/+/machine1";
    m2.templateTopicSample = "/plant1/line1/machine1";
    assertEquals(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
        MappingsRepresentation.isTemplateTopicTemplateAndTopicSampleValid(m2.templateTopic, m2.templateTopicSample)
            .get(0));

    Mapping m3 = new Mapping();
    m3.templateTopic = "/plant1/+/machine1/modul1";
    m3.templateTopicSample = "/plant1/line1/machine1";
    assertEquals(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
        MappingsRepresentation.isTemplateTopicTemplateAndTopicSampleValid(m3.templateTopic, m3.templateTopicSample)
            .get(0));

  }

  @Test
  void testSplitTopic() {

    String t1 = "/d1/e1/f1/";
    String[] r1 = Mapping.splitTopicExcludingSeparatorAsArray(t1);
    log.info("My topicSplit: {}", Arrays.toString(r1));
    assertArrayEquals(new String[] {"d1", "e1", "f1"}, r1);


    String t2 = "///d1/e1/f1///";
    String[] r2 = Mapping.splitTopicExcludingSeparatorAsArray(t2);
    log.info("My topicSplit: {}, size: {}", Arrays.toString(r2), r2.length);
    assertArrayEquals(new String[] {"d1", "e1", "f1"}, r2);


    String t3 = "///d1/e1/f1///";
    String[] r3 = Mapping.splitTopicIncludingSeparatorAsArray(t3);
    log.info("My topicSplit: {}", Arrays.toString(r3));

    assertArrayEquals(new String[] {"/","d1", "/", "e1", "/","f1", "/"}, r3);

  }


  @Test
  void testMQTTConfigurationIsActive() {
    MQTTConfiguration conf = null;

    log.info("My configuration is active: {}", MQTTConfiguration.isActive(conf));
    assertEquals(false, MQTTConfiguration.isActive(conf));
  }

  @Test
  void testNeedsRepair() {

    ProcessingContext p1 = new ProcessingContext();
    p1.addCardinality("value1",   5);
    p1.addCardinality("value2",   5);
    p1.addCardinality(PayloadProcessor.SOURCE_ID, 1);
    // log.info("My neeRepair1: {}", p1.needsRepair);
    assertEquals(false, p1.needsRepair);


    ProcessingContext p2 = new ProcessingContext();
    p2.addCardinality("value1",   5);
    p2.addCardinality("value2",   4);
    p2.addCardinality(PayloadProcessor.SOURCE_ID, 1);
    // log.info("My neeRepair1: {}", p2.needsRepair);
    assertEquals(true, p2.needsRepair);

  }

}
