---
title: Code templates
---

### Code templates for transformation types involving JavaScript {#code-templates}

The Dynamic Mapper provides predefined code templates for each transformation type and direction. These templates
serve as starting points for building your mappings and can be customized according to your requirements.

**Using Code Templates:** Code templates are available in the mapping editor when creating or editing mappings.
You can view, customize, and test these templates before applying them to your mappings.

A **Code Template** is the JavaScript starting point that is copied verbatim into every new mapping that uses a
JavaScript-based transformation type. Each mapping maintains its own independent copy, modifying a template after
a mapping has been created does *not* affect existing mappings.

| Template Type | Direction | Purpose | Editable? |
|---|---|---|---|
| `INBOUND_SMART_FUNCTION` | Inbound | Starting template for inbound Smart Function mappings | Shipped templates are read-only. Use **Duplicate** to create an editable copy. |
| `OUTBOUND_SMART_FUNCTION` | Outbound | Starting template for outbound Smart Function mappings | Shipped templates are read-only. Use **Duplicate** to create an editable copy. |
| `SHARED` | Both | Evaluated before every Smart Function execution. Define helper functions and constants here that are available as globals in all Smart Functions without any import statement. The mapper never overwrites it. You cannot delete or duplicate it, only edit or empty it. | Yes |
| `SYSTEM` | Both | Read-only runtime definitions, such as `atob`, `btoa`, and the substitution helper classes. The mapper replaces it with the shipped version at every service start. | No (read-only) |

Only administrators can save, duplicate, rename, or delete templates. The **Init internal code templates** action in
the **More** menu restores all shipped templates to factory defaults. It keeps the Shared template and the templates
you created.

The templates currently present in your tenant are listed in the gallery below.
