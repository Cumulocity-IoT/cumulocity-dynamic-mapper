# Dynamic Mapper Service for Cumulocity

## Changes

In this release 5.5.0 of the Cumulocity Dynamic Mapper, there is a breaking change concerning the naming of roles and the enforcement of permissions for features.

### Name of the roles
The following roles have been renamed:

**Previous roles:**
```
    ROLE_DYNAMIC_MAPPING_ADMIN
    ROLE_DYNAMIC_MAPPING_CREATE
    ROLE_DYNAMIC_MAPPING_HTTP_CONNECTOR_CREATE
```

**New roles:**

```
    ROLE_DYNAMIC_MAPPER_ADMIN
    ROLE_DYNAMIC_MAPPER_CREATE
    ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE
```

### Permission Enforcement
Permissions are now strictly enforced, whereas they were not enforced in previous versions.

**Default permissions:**
- Users without any assigned roles will have **read-only access** to:
  - Mappings
  - Service configuration
  - Connectors

**Enhanced permissions:**
- To create, modify, or delete resources, users must be granted the appropriate roles listed above
- Administrative functions require the `ROLE_DYNAMIC_MAPPER_ADMIN` role
- Creating new mappings requires the `ROLE_DYNAMIC_MAPPER_CREATE` role
- Creating HTTP connectors requires the `ROLE_DYNAMIC__HTTP_CONNECTOR_CREATE` role

To be able to use more feature additional roles have to be granted:
     <div class="table-responsive table-width-80">
      <table class="table _table-striped">
        <thead class="thead-light">
          <tr>
            <th style="width: 40%;">Dynamic Mapper Feature</th>
            <th class="text-center" style="width: 20%;">No role</th>
            <th class="text-center" style="width: 20%;">Create</th>
            <th class="text-center" style="width: 20%;">Admin</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>Mapping Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Mapping Create/Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Mapping Delete</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Mapping Activate/Deactivate</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Mapping Snoop/Debug/Filter</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Connector Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Connector Create/Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Connector Delete</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Connector Activate/Deactivate</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Service Configuration Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Service Configuration Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
        </tbody>
      </table>
    </div>
    
### Migration Notes
- Update any existing role assignments to use the new role names
- Review user permissions and assign appropriate roles to maintain existing functionality
- Users who previously had implicit access to create/modify features will need explicit role assignments
