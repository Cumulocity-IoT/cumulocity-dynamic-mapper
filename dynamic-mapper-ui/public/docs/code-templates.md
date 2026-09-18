---
title: Code templates
---

### Code Templates for transformation types involving JavaScript {#code-templates}

The Dynamic Mapper provides predefined code templates for each transformation type and direction. These templates
serve as starting points for building your mappings and can be customized according to your requirements.

**Using Code Templates:** Code templates are available in the mapping editor when creating or editing mappings.
You can view, customize, and test these templates before applying them to your mappings.

A **Code Template** is the JavaScript starting point that is copied verbatim into every new mapping that uses a
JavaScript-based transformation type. Each mapping maintains its own independent copy — modifying a template after
a mapping has been created does *not* affect existing mappings.

| Template Type | Direction | Purpose | Editable? |
|---|---|---|---|
| `INBOUND_SMART_FUNCTION` | Inbound | Starting template for inbound Smart Function mappings | Yes (duplicate system template to customise) |
| `OUTBOUND_SMART_FUNCTION` | Outbound | Starting template for outbound Smart Function mappings | Yes (duplicate system template to customise) |
| `SHARED` | Both | Evaluated before every Smart Function execution — define helper functions and constants here that are available as globals in all Smart Functions without any import statement | Yes |
| `SYSTEM` | — | Read-only canonical defaults maintained by the mapper. Use **Duplicate** to create a customisable copy. The *Init system code templates* action restores all system templates to factory defaults (your custom templates are not affected). | No (read-only) |

The templates currently present in your tenant are listed in the
[gallery](#code-template-gallery) below.
