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

package dynamic.mapping.processor;

public class ProcessingException extends Exception {
    Throwable originException = null;
    int httpStatusCode = 0;
    public ProcessingException(String errorMessage) {
        super(errorMessage);
    }

    public ProcessingException(String errorMessage, Throwable e) {
        super(errorMessage, e);
        this.originException = e;
    }

    public ProcessingException(String errorMessage, Throwable e, int httpStatusCode) {
        super(errorMessage, e);
        this.httpStatusCode = httpStatusCode;
        this.originException = e;
    }

    public ProcessingException(String errorMessage, int httpStatusCode) {
        super(errorMessage);
        this.httpStatusCode = httpStatusCode;
    }
    public int getHttpStatusCode() {
        return httpStatusCode;
    }
    public Throwable getOriginException() {
        return originException;
    }
}