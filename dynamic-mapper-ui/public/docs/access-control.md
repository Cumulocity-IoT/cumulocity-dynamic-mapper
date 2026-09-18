---
title: Managing permissions
---

### Managing permissions for Dynamic Mapper features {#access-control}

Dynamic Mapper uses role-based access control. The table below shows the permissions for each role:

- **No role**: Read-only access to mappings, connectors, and service configuration.
- **Create role**: All read permissions plus full mapping management (create, edit, delete, activate/deactivate,
  debug/filter).
- **Admin role**: All **Create role** permissions plus connector management and service configuration editing.

:::caution
**Security Best Practice:** Assign the minimum required role to users. Regular users typically need only
**Create role** to work with mappings. Reserve **Admin role** for system administrators who manage
infrastructure-level settings like connectors and service configuration.
:::

Key points:

- Only users with **Create role** or higher can modify mappings.
- Only users with **Admin role** can manage connectors or edit service configuration.
- All roles can read mappings, connectors, and service configuration.

| Dynamic Mapper Feature | No role | Create | Admin |
|---|:---:|:---:|:---:|
| **Mapping Read** | ✓ | ✓ | ✓ |
| **Mapping Create/Edit** | – | ✓ | ✓ |
| **Mapping Delete** | – | ✓ | ✓ |
| **Mapping Activate/Deactivate** | – | ✓ | ✓ |
| **Mapping Debug/Filter** | – | ✓ | ✓ |
| **Connector Read** | ✓ | ✓ | ✓ |
| **Connector Create/Edit** | – | – | ✓ |
| **Connector Delete** | – | – | ✓ |
| **Connector Activate/Deactivate** | – | – | ✓ |
| **Service Configuration Read** | ✓ | ✓ | ✓ |
| **Service Configuration Edit** | – | – | ✓ |

To configure roles, navigate to **Administration → Role Management** in Cumulocity. Look for "Dynamic Mapper"
permissions and assign them to global roles or specific user groups based on your organization's needs.

