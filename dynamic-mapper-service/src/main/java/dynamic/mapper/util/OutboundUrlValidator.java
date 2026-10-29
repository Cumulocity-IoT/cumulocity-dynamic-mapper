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

package dynamic.mapper.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Guards connectors that call a user-configured URL (REST polling, webhook) against server-side request forgery.
 * Only plain http(s) URLs without credentials in the authority are accepted, and the host must not resolve to an
 * address that is never a legitimate remote target: loopback (the service itself, e.g. its actuator endpoints),
 * link-local (cloud metadata services such as 169.254.169.254), the wildcard address or multicast. Private network
 * ranges stay allowed, since webhooks to services inside a customer network or cluster are a normal use.
 * A host that does not resolve is accepted here; the request itself then fails.
 */
public final class OutboundUrlValidator {

    private OutboundUrlValidator() {
    }

    /**
     * @return the URL unchanged, if it is acceptable
     * @throws IllegalArgumentException if the URL is malformed or points to a forbidden target
     */
    public static String requireSafe(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL is not valid: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Credentials in the URL are not allowed, use the authentication settings");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return url;
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Host '" + host + "' resolves to an address that is not allowed ("
                        + address.getHostAddress() + ")");
            }
        }
        return url;
    }
}
