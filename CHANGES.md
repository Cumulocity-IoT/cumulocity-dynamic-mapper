# Dynamic Mapper Service for Cumulocity

## Changes

In this release 5.5.0 of the Cumulocity Dynamic Mapper there is a breaking change concerning the name of the roles and the enforcement of the permissions.

### Name of the roles
So far the following roles where used:

```
    ROLE_MAPPING_ADMIN
    ROLE_MAPPING_CREATE
    ROLE_MAPPING_HTTP_CONNECTOR_CREATE
```

These have been renamed to:

```
    ROLE_MAPPER_ADMIN
    ROLE_MAPPER_CREATE
    ROLE_MAPPER_HTTP_CONNECTOR_CREATE
```

### Enforcement of the permissions
The permissions were not enforced. This is changes in the current release.
This means if you don't assign any roles, the user will only be able to see/read information on mappings, Service configuration and connectors.
To be able to use more feature additional roles have to be granted:
     <div class="table-responsive table-width-80">
      <table class="table _table-striped">
        <thead class="thead-light">
          <tr>
            <th style="width: 40%;">Dynamic Mapper Functionality</th>
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
