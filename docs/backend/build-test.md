# Backend Build & Test

The backend is built with Maven (Java 21).

```bash
# Build all modules (produces deployable ZIP in dynamic-mapper-service/target/)
mvn clean package

# Build a single module
cd dynamic-mapper-service && mvn clean package

# Run all tests
cd dynamic-mapper-service && mvn test

# Run a specific test class
cd dynamic-mapper-service && mvn test -Dtest=GraalVMTest

# Run a specific test method
cd dynamic-mapper-service && mvn test -Dtest=GraalVMTest#testMethod
```
