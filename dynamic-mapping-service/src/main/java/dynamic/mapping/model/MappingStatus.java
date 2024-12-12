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

package dynamic.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {})
public class MappingStatus implements Serializable {

  public static MappingStatus UNSPECIFIED_MAPPING_STATUS;
  public static final String IDENT_UNSPECIFIED_MAPPING = "UNSPECIFIED";

  static {
    UNSPECIFIED_MAPPING_STATUS = new MappingStatus(IDENT_UNSPECIFIED_MAPPING, IDENT_UNSPECIFIED_MAPPING,
        IDENT_UNSPECIFIED_MAPPING, null, "#", "#", 0, 0, 0,
        0, null);
  }

  @NotNull
  public String id;

  @NotNull
  public String name;

  @NotNull
  public String ident;

  @NotNull
  public Direction direction;

  @NotNull
  public String mappingTopic;

  @NotNull
  public String publishTopic;

  @NotNull
  public long messagesReceived;

  @NotNull
  public long errors;

  @NotNull
  public long snoopedTemplatesActive;

  @NotNull
  public long snoopedTemplatesTotal;

  @NotNull
  @Setter
  public String loadingError;

  @Override
  public boolean equals(Object m) {
    return (m instanceof MappingStatus) && id == ((MappingStatus) m).id;
  }

  public void reset() {
    messagesReceived = 0;
    errors = 0;
    loadingError = "";
    snoopedTemplatesActive = 0;
    snoopedTemplatesTotal = 0;
  }
}