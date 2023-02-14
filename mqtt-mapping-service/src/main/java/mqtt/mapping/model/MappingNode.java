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

package mqtt.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@ToString()
public class MappingNode extends TreeNode {
    public static MappingNode createMappingNode(InnerNode parent, Mapping mapping, String level) {
        MappingNode node = new MappingNode();
        node.setParentNode(parent);
        node.setMapping(mapping);
        node.setLevel(level);
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        return node;
    }

    @Setter
    @Getter
    private Mapping mapping;

    @Setter
    @Getter
    private long deviceIdentifierIndex;

    public boolean isMappingNode() {
        return true;
    }

    public List<TreeNode> resolveTopicPath(List<String> tp) throws ResolveException {
        log.debug("Resolved mapping: {}, tp.size(): {}", mapping, tp.size());
        if (tp.size() == 0) {
            return new ArrayList<TreeNode>(Arrays.asList(this));
        } else {
            String remaining = String.join("/", tp);
            throw new ResolveException(
                    "No mapping registered for this path: " + this.getAbsolutePath() + remaining + "!");
        }
    }
}
