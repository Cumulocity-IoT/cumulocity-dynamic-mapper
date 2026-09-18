---
title: Transformation types
---

### Transformation types {#transformation-types}

The Dynamic Mapper offers four powerful ways to transform your data between external formats and Cumulocity IoT.
Each transformation type provides different levels of control and flexibility, allowing you to choose the
approach that best fits your use case:


A new mapping uses a **Smart Function** unless you change it: with **Expert Mode** switched off the creation
dialog does not offer the choice at all. Enable Expert Mode to select JSONata or a Java Extension instead.

**Quick-reference decision guide:**

| If you need… | Use this type | Notes |
|---|:---:|---|
| Simple field-to-field mapping | **JSONata** | Declarative, no JavaScript knowledge required |
| Complex expressions, conditional logic, math | **JSONata** | JSONata supports functions, predicates, and aggregations natively |
| Familiar imperative JavaScript syntax per-field | **Smart Function** | JavaScript Substitutions have been removed in release 6.3; Smart Functions offer a superset of capabilities |
| Full control over the output payload | **Smart Function** | You build the entire target object; all substitution types are bypassed |
| Array inputs producing multiple C8Y objects | **Smart Function** | Return an array from the function; each element becomes a separate C8Y request |
| Binary / Protobuf / unknown-format payloads | **Smart Function + Any Payload** | Payload is passed as a Base64 string; decode and parse it in your function |
| Java type safety, existing Java libraries, server-side execution | **Java Extension** | Packaged as a JAR uploaded to the Cumulocity tenant; compiled JVM performance |

#### Defining a substitution using JSONata {#jsonata-substitution}

JSONata is a powerful query and transformation language for JSON that supports path expressions, predicates,
functions, and aggregations. Use JSONata when you need declarative, expression-based transformations for
straightforward field mappings without requiring programming knowledge.

**[Learn more about defining substitutions using JSONata →](/c8y-pkg-dynamic-mapper/introduction/jsonata)**

#### Defining the payload transformation using a Smart Function (JavaScript) {#javascript-smart-function}

Smart Functions provide complete programmatic control over the entire transformation logic using JavaScript,
allowing you to define the full payload structure rather than just substitutions. Use Smart Functions when you
need maximum flexibility with access to device inventory data, complex business logic, multiple outputs from a
single input message, or state management across messages.

**[Learn more about Smart Functions and metadata usage →](/c8y-pkg-dynamic-mapper/introduction/smartfunction)**

#### Removed: Substitution as JavaScript (release 6.3) {#javascript-substitution}

:::important Removed in release 6.3
**The transformation type Substitution as JavaScript is no longer supported.** Existing mappings of this type
are automatically deactivated on startup and are no longer executed. The only permitted operations are **Export**
and **Delete**. Editing, testing, activating, or duplicating such mappings is not supported.
:::

Migrate to **Smart Function (JavaScript)**:

1. Export the affected mapping via the **Export** action in the mapping grid.
2. Create a new mapping with transformation type **Smart Function (JavaScript)**. Instead of returning a
   `SubstitutionResult`, the function returns a fully-built Cumulocity object directly. See
   [Smart Functions →](/c8y-pkg-dynamic-mapper/introduction/smartfunction) for the API and code templates.
3. Use the built-in **Test** feature to verify the migrated mapping.
4. Activate the new mapping and delete the original deprecated mapping.

**[View migration guide for Substitution as JavaScript →](/c8y-pkg-dynamic-mapper/introduction/smartfunction)**

#### Defining the payload transformation using Java Extensions {#java-extension}

Java Extensions provide enterprise-grade transformation capabilities by allowing you to write custom
transformation logic in Java. This approach offers type safety, superior performance, and full access to the Java
ecosystem including third-party libraries and Cumulocity Java SDK. Use Java Extensions when you need to handle
complex data transformations, implement sophisticated business logic, require strong type checking, or need to
integrate with existing Java-based systems.

**[Learn more about Java Extensions →](/c8y-pkg-dynamic-mapper/introduction/javaextension)**

