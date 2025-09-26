/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.model;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dynamic.util.LogLevelExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
@ExtendWith(LogLevelExtension.class)
public class MappingsRepresentationTest {

    @Test
    void testRegexpNormalizeTopic() {

        String topic1 = "/rom/hamburg/madrid/#/";
        String nt1 = topic1.replaceAll(Mapping.REGEXP_REMOVE_TRAILING_SLASHES, "#");
        assertEquals(nt1, "/rom/hamburg/madrid/#");

        String topic2 = "////rom/hamburg/madrid/#/////";
        String nt2 = topic2.replaceAll(Mapping.REGEXP_REDUCE_LEADING_TRAILING_SLASHES, "/");
        assertEquals(nt2, "/rom/hamburg/madrid/#/");

    }

    @Test
    void testNormalizeTopic() {

        String topic1 = "/rom/hamburg/madrid/#/";
        assertEquals(Mapping.normalizeTopic(topic1), "/rom/hamburg/madrid/#");

        String topic2 = "///rom/hamburg/madrid/+//";
        assertEquals(Mapping.normalizeTopic(topic2), "/rom/hamburg/madrid/+/");

    }

    @Test
    void testIsMappingTopicSampleValid() {

        Mapping m1 = Mapping.builder().mappingTopic("/device/+/east/").mappingTopicSample("/device/us/east/").build();
        assertEquals(new ArrayList<ValidationError>(),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m1.getMappingTopic(), m1.getMappingTopicSample()));

        Mapping m2 = Mapping.builder().mappingTopic("/device/#").mappingTopicSample("/device/us/east/").build();
        assertEquals(new ArrayList<ValidationError>(Arrays.asList(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name)),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m2.getMappingTopic(), m2.getMappingTopicSample()));

        Mapping m3 = Mapping.builder().mappingTopic("/device/").mappingTopicSample("/device/").build();
        assertEquals(new ArrayList<ValidationError>(),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m3.getMappingTopic(), m3.getMappingTopicSample()));
    }

    @Test
    void testSubstitutionIsSorted() {

        Substitution s1 = Substitution.builder().pathSource("p1s").pathTarget("p1t").build();
        Substitution s2 = Substitution.builder().pathSource("p2s").pathTarget("source.id").build();
        Substitution s3 = Substitution.builder().pathSource("p3s").pathTarget("p3t").build();
        Mapping m1 = Mapping.builder().targetAPI(API.EVENT).substitutions(new Substitution[] { s1, s2, s3 }).build();

        assertEquals("p1s", m1.getSubstitutions()[0].getPathSource());
        m1.sortSubstitutions();
        log.info("My substitutions {}", Arrays.toString(m1.getSubstitutions()));
        assertEquals("p1s", m1.getSubstitutions()[0].getPathSource());

    }

    void testMappingTopicMatchesMappingTopicSample() {

        Mapping m1 = Mapping.builder().mappingTopic("/plant1/+/machine1").build();
        assertEquals(0, Mapping
                .isMappingTopicAndMappingTopicSampleValid(m1.getMappingTopic(), m1.getMappingTopicSample()).size() == 0);

        Mapping m2 = Mapping.builder().mappingTopic("/plant2/+/machine1").mappingTopicSample("/plant1/line1/machine1")
                .build();

        assertEquals(ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
                Mapping.isMappingTopicAndMappingTopicSampleValid(m2.getMappingTopic(), m2.getMappingTopicSample())
                        .get(0));

        Mapping m3 = Mapping.builder().mappingTopic("/plant1/+/machine1/modul1")
                .mappingTopicSample("/plant1/line1/machine1").build();
        assertEquals(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
                Mapping.isMappingTopicAndMappingTopicSampleValid(m3.getMappingTopic(), m3.getMappingTopicSample())
                        .get(0));

    }

    @Test
    void testSplitTopic() {

        String t1 = "/d1/e1/f1/";
        String[] r1 = Mapping.splitTopicExcludingSeparatorAsArray(t1, false);
        log.info("My topicSplit: {}", Arrays.toString(r1));
        assertArrayEquals(new String[] { "/", "d1", "e1", "f1" }, r1);

        String t2 = "///d1/e1/f1///";
        String[] r2 = Mapping.splitTopicExcludingSeparatorAsArray(t2, false);
        log.info("My topicSplit: {}, size: {}", Arrays.toString(r2), r2.length);
        assertArrayEquals(new String[] { "/", "d1", "e1", "f1" }, r2);

        String t3 = "///d1/e1/f1///";
        String[] r3 = Mapping.splitTopicExcludingSeparatorAsArray(t3, true);
        log.info("My topicSplit: {}, size: {}", Arrays.toString(r3), r3.length);
        assertArrayEquals(new String[] { "d1", "e1", "f1" }, r3);

        String t4 = "///d1/e1/f1///";
        String[] r4 = Mapping.splitTopicIncludingSeparatorAsArray(t4);
        log.info("My topicSplit important: {}", Arrays.toString(r4));

        assertArrayEquals(new String[] { "/", "d1", "/", "e1", "/", "f1", "/" }, r4);

    }

    @Test
    void testPatternFor_isMappingTopicAndSubscriptionTopicValid() {
        // test if the templateTopic is covered by the subscriptionTopic
        BiFunction<String, String, Boolean> topicMatcher = (st,
                tt) -> (Pattern.matches(String.join("[^\\/]+", st.replace("/", "\\/").split("\\+")).replace("#", ".*"),
                        tt));
        // append trailing null character to avoid that the last "+" is swallowd
        String st = "binary/+" + "\u0000";
        String mt = "binary/+" + "\u0000";
        ;
        boolean error = (!topicMatcher.apply(st, mt));

        assertFalse(error);
        log.info(
                String.join("[^\\/]+", st.replace("/", "\\/").split("\\+")).replace("#", ".*"));
    }
}
