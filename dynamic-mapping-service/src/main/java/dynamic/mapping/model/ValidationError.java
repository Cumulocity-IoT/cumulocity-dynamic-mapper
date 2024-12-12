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

public enum ValidationError {
  Only_One_Multi_Level_Wildcard,
  Only_One_Single_Level_Wildcard,
  Multi_Level_Wildcard_Only_At_End,
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used,
  One_Substitution_Defining_Device_Identifier_Must_Be_Used,
  MappingTopic_Not_Unique,
  MappingTopic_Must_Not_Be_Substring_Of_Other_MappingTopic, 
  Target_Template_Must_Be_Valid_JSON, 
  Source_Template_Must_Be_Valid_JSON, 
  No_Multi_Level_Wildcard_Allowed_In_MappingTopic,
  Device_Identifier_Must_Be_Selected, 
  MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  FilterOutbound_Must_Be_Unique
}
