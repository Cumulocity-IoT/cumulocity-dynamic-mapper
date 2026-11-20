/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.configuration;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import dynamic.mapper.model.Direction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString()
@NoArgsConstructor
@AllArgsConstructor
public class CodeTemplate implements Cloneable {
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String id;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String name;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String description;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public TemplateType templateType;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String code;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean internal;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean readonly;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean defaultTemplate = false;

    /**
     * A message the template is written for (JSON text), declared in the template header as
     * {@code @samplePayload}. The UI uses it as the sample payload of a new mapping, so the first
     * dry run exercises the template's own fields instead of a generic sample. Optional: absent
     * for templates that do not declare one.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String samplePayload;

    /**
     * A topic matching {@link #samplePayload}, declared as {@code @sampleTopic}. Optional.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String sampleTopic;

    /**
     * The topic pattern an OUTBOUND template publishes to (for example {@code command/+}), declared
     * as {@code @publishTopic}. The UI uses it as the publish topic of a new mapping. Optional; not
     * used for INBOUND templates.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String publishTopic;

    /**
     * The topic pattern an INBOUND template subscribes to (for example {@code sensors/+}), declared
     * as {@code @mappingTopic}. The UI uses it as the mapping topic of a new mapping. Optional; not
     * used for OUTBOUND templates.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String mappingTopic;

    /**
     * The execution filter an OUTBOUND template is written for (a JSONata expression such as
     * {@code $exists(c8y_Command)} or {@code true}), declared as {@code @filterMapping}. The UI uses
     * it as the "Filter execution mapping" of a new mapping, so the user does not start with an empty,
     * required filter. Optional; not used for INBOUND templates.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String filterMapping;

    /**
     * The Cumulocity API the template works with, declared as {@code @targetAPI}: the API it creates
     * data in (INBOUND) or listens to (OUTBOUND), one of {@code MEASUREMENT}, {@code EVENT},
     * {@code ALARM}, {@code INVENTORY}, {@code OPERATION}. The UI preselects it for a new mapping;
     * without it the mapping starts with MEASUREMENT. Optional.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    public String targetAPI;

    /**
     * The direction this template applies to, derived from its {@link TemplateType} and serialized
     * for clients that group templates by direction.
     *
     * <p>It is deliberately not a stored field: it used to be set from a separate {@code @direction}
     * annotation that could contradict {@code templateType}, and a client that dropped it on a
     * round-trip persisted a null. Read-only on the wire, so a PUT can no longer put the two out of
     * step -- incoming values are ignored rather than rejected.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Direction getDirection() {
        return templateType != null ? templateType.getDirection() : null;
    }

    @Override
    public CodeTemplate clone() {
        try {
            return (CodeTemplate) super.clone();
        } catch (CloneNotSupportedException e) {
            // This should never happen since we implement Cloneable
            throw new RuntimeException("Clone not supported", e);
        }
    }
}
