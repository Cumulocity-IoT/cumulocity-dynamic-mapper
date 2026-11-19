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

package dynamic.mapper.core;

/**
 * An upload of a processor extension was rejected. {@link #getReason()} tells the caller which HTTP status fits.
 */
public class ExtensionUploadException extends Exception {

    public enum Reason {
        /** The name or the file is not usable: bad name, not a JAR, no {@code extension-external.yaml}. */
        INVALID,
        /** The file is larger than {@link ExtensionManager#MAX_EXTENSION_BYTES}. */
        TOO_LARGE,
        /** An extension with that name exists and the request did not ask to replace it, or it is built in. */
        NAME_TAKEN,
        /** The file was stored, but no extension of it could be loaded; the upload was rolled back. */
        NOT_LOADED
    }

    private final Reason reason;

    public ExtensionUploadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
