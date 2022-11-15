#!/bin/sh

run_protoc_extension () {
    protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/CustomEvent.desc CustomEvent.proto
}
run_protoc_backend () {
    protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/CustomMeasurement.desc CustomMeasurement.proto
}


if [ "$1" == "backend" ]
then
    ( cd backend ; run_protoc_backend )
elif [ "$1" == "extension" ]
then
    ( cd extension ; run_protoc_extension )
else
    echo "Use additional parameter backend or extension with command."
fi
