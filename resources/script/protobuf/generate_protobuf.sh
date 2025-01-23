#!/bin/sh

#
# Copyright (c) 2022-2025 Cumulocity GmbH.
#
# SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christof Strack, Stefan Witschel
#
#

run_protoc_extension () {
    protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/CustomEvent.desc CustomEvent.proto
}
run_protoc_backend () {
    protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/StaticCustomMeasurement.desc StaticCustomMeasurement.proto
    protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/InternalCustomAlarm.desc InternalCustomAlarm.proto
}


if [ "$1" == "backend" ]
then
    ( cd mqtt-mapping-service ; run_protoc_backend )
elif [ "$1" == "extension" ]
then
    ( cd mqtt-mapping-extension ; run_protoc_extension )
else
    echo "Use additional parameter backend or extension with command."
fi
