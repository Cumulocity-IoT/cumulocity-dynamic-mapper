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

        Mapping m1 = new Mapping();
        m1.setMappingTopic("/device/+/east/");
        m1.setMappingTopicSample("/device/us/east/");
        assertEquals(new ArrayList<ValidationError>(),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m1.mappingTopic, m1.mappingTopicSample));

        Mapping m2 = new Mapping();
        m2.setMappingTopic("/device/#");
        m2.setMappingTopicSample("/device/us/east/");
        assertEquals(new ArrayList<ValidationError>(Arrays.asList(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name)),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m2.mappingTopic, m2.mappingTopicSample));

        Mapping m3 = new Mapping();
        m3.setMappingTopic("/device/");
        m3.setMappingTopicSample("/device/");
        assertEquals(new ArrayList<ValidationError>(),
                Mapping.isMappingTopicAndMappingTopicSampleValid(m3.mappingTopic, m3.mappingTopicSample));
    }

    @Test
    void testSubstitutionIsSorted() {

        Mapping m1 = new Mapping();
        m1.targetAPI = API.EVENT;
        Substitution s1 = new Substitution();
        s1.pathSource = "p1s";
        s1.pathTarget = "p1t";
        Substitution s2 = new Substitution();
        s2.pathSource = "p2s";
        s2.pathTarget = "source.id";
        Substitution s3 = new Substitution();
        s3.pathSource = "p3s";
        s3.pathTarget = "p3t";
        m1.substitutions = new Substitution[] { s1, s2, s3 };

        assertEquals("p1s", m1.substitutions[0].pathSource);
        m1.sortSubstitutions();
        log.info("My substitutions {}", Arrays.toString(m1.substitutions));
        assertEquals("p1s", m1.substitutions[0].pathSource);

    }

    void testMappingTopicMatchesMappingTopicSample() {

        Mapping m1 = new Mapping();
        m1.mappingTopic = "/plant1/+/machine1";
        m1.mappingTopicSample = "/plant1/line1/machine1";
        assertEquals(0, Mapping
                .isMappingTopicAndMappingTopicSampleValid(m1.mappingTopic, m1.mappingTopicSample).size() == 0);

        Mapping m2 = new Mapping();
        m2.mappingTopic = "/plant2/+/machine1";
        m2.mappingTopicSample = "/plant1/line1/machine1";
        assertEquals(ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
                Mapping.isMappingTopicAndMappingTopicSampleValid(m2.mappingTopic, m2.mappingTopicSample)
                        .get(0));

        Mapping m3 = new Mapping();
        m3.mappingTopic = "/plant1/+/machine1/modul1";
        m3.mappingTopicSample = "/plant1/line1/machine1";
        assertEquals(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
                Mapping.isMappingTopicAndMappingTopicSampleValid(m3.mappingTopic, m3.mappingTopicSample)
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
