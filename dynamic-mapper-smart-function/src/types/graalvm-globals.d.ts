/**
 * Ambient declarations for the globals the GraalVM JavaScript runtime injects into Smart
 * Functions. They are not part of the Smart Function API surface itself, but templates and user
 * code legitimately use them, so the template type-check (`npm run check:templates`) needs to
 * know they exist.
 */

/**
 * GraalVM polyglot access to Java classes.
 *
 * Note the fragility this represents: the fully-qualified name is a **string**, so moving or
 * renaming a Java class compiles cleanly and fails at runtime, and the class must additionally be
 * on the host-access allow-list. Prefer the Smart Function API over `Java.type` where one exists.
 */
declare const Java: {
  /** Resolves a Java class by fully-qualified name, e.g. `Java.type('java.util.HashMap')`. */
  type(fullyQualifiedName: string): any;
};

/** Base64-decode. Provided by the runtime; see the binary-helpers section of the Smart Function docs. */
declare function atob(encoded: string): string;

/** Base64-encode. Provided by the runtime. */
declare function btoa(raw: string): string;
