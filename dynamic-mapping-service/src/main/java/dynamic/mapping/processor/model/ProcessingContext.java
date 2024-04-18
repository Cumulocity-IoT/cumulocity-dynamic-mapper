/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.model;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.model.QOS;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import dynamic.mapping.processor.ProcessingException;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
@NoArgsConstructor
/*
 * The class <code>ProcessingContext</code> collects all relevant information:
 * <code>mapping</code>, <code>topic</code>, <code>payload</code>,
 * <code>requests</code>, <code>error</code>, <code>processingType</code>,
 * <code>cardinality</code>, <code>needsRepair</code>
 * when a <code>mapping</code> is applied to an inbound <code>payload</code>
 */
public class ProcessingContext<O> {
    private Mapping mapping;

    private String topic;

    private QOS qos;

    private String resolvedPublishTopic;

    private O payload;

    private byte[] payloadRaw;

    private List<C8YRequest> requests = new ArrayList<C8YRequest>();

    private List<Exception> errors  = new ArrayList<Exception>();

    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private Map<String, Integer> cardinality = new HashMap<String, Integer>();

    private MappingType mappingType;

    private Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = new HashMap<String, List<MappingSubstitution.SubstituteValue>>();
    
    private boolean sendPayload = false;

    private boolean needsRepair = false;

    private String tenant;

    private ServiceConfiguration serviceConfiguration;

    private boolean supportsMessageContext = false;


    public static final String SOURCE_ID = "source.id";

    public boolean hasError() {
        return errors != null && errors.size() > 0;
    }

    public int addRequest(C8YRequest c8yRequest) {
        requests.add(c8yRequest);
        return requests.size() - 1;
    }

    /*
     * Keep track of the extracted size of every extracted values for a
     * <code>pathTarget</code>
     * 
     * @param pathTarget jsonPath of target in a substitution
     * 
     * @param card cardinality of this <code>pathTarget</code> found when extracting
     * values from the payload
     * 
     * @return true if all added cardinalities are the same, fals if at least two
     * different cardinalities exist.
     */
    public void addCardinality(String pathTarget, Integer card) {
        cardinality.put(pathTarget, card);
        Set<Map.Entry<String, Integer>> entries = cardinality.entrySet();
        Stream<Entry<String, Integer>> stream1 = entries.stream()
                .filter(e -> !ProcessingContext.SOURCE_ID.equals(e.getKey()));
        Map<Integer, Long> collect = stream1.collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting()));
        needsRepair = (collect.size() != 1);
    }

    public C8YRequest getCurrentRequest() {
        return requests.get(requests.size()-1);
    }

    public void addError(ProcessingException processingException) {
        errors.add(processingException);
    }

}