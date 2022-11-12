cd backend
protoc --proto_path=src/main/resources/protobuf --java_out=src/main/java --descriptor_set_out=src/main/resources/protobuf/CustomMeasurement.desc CustomMeasurement.proto
cd ..