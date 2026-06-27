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

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal MAJOR.MINOR.PATCH semantic version value type. Supports parsing,
 * validation, comparison, and the three standard bump operations.
 */
public final class SemVer implements Comparable<SemVer> {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    public static final SemVer INITIAL = new SemVer(1, 0, 0);

    /** Null-safe comparator that sorts {@code null} versions last. */
    public static final Comparator<String> STRING_COMPARATOR = (a, b) -> {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        try {
            return SemVer.parse(a).compareTo(SemVer.parse(b));
        } catch (IllegalArgumentException e) {
            return a.compareTo(b);
        }
    };

    private final int major;
    private final int minor;
    private final int patch;

    public SemVer(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static SemVer parse(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version must not be null");
        }
        Matcher m = PATTERN.matcher(version.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Invalid semver '" + version + "': expected MAJOR.MINOR.PATCH with non-negative integers");
        }
        return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public static boolean isValid(String version) {
        return version != null && PATTERN.matcher(version.trim()).matches();
    }

    public SemVer bumpPatch() {
        return new SemVer(major, minor, patch + 1);
    }

    public SemVer bumpMinor() {
        return new SemVer(major, minor + 1, 0);
    }

    public SemVer bumpMajor() {
        return new SemVer(major + 1, 0, 0);
    }

    @Override
    public int compareTo(SemVer o) {
        if (this.major != o.major) return Integer.compare(this.major, o.major);
        if (this.minor != o.minor) return Integer.compare(this.minor, o.minor);
        return Integer.compare(this.patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SemVer)) return false;
        SemVer o = (SemVer) obj;
        return major == o.major && minor == o.minor && patch == o.patch;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * major + minor) + patch;
    }
}
