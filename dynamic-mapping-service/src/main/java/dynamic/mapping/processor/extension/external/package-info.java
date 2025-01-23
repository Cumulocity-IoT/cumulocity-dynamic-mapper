/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

/**
 * This module holds all the externally loaded processor extensions.
 * External processor extensions have to:
 * 1. implement the interface <code>ProcessorExtension<O></code>
 * 2. be registered in the properties file <code>/dynamic-mapping-extension/src/main/resources/extension-external.properties</code>
 * 3. be developed/packed in the maven module <code>/dynamic-mapping-extension</code>. NOT in this maven module.
 * 4. be uploaded through the Web UI
 * <p>

 * </p>
 *
 * @since 1.0
 * @author christof.strack
 * @version 1.1
 */

package dynamic.mapping.processor.extension.external;
