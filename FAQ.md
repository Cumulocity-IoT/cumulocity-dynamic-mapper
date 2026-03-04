## Frequently Asked Questions

**Q: I'm getting "Defined microservice settings category "dynMappingService" is not valid. Must be unique within tenant." What should I do?**

**A:** This is due to a new tenant credentials policy that Cumulocity rolled out, which prevents unauthorized access to tenant options. Due to a name change in release 6.x, you must delete all existing tenant options in the `dynMappingService` category using the following command:
```
c8y tenantoptions getForCategory --category dynMappingService --outputTemplate 'std.objectFields(output)' | c8y tenantoptions delete --category dynMappingService
```
> **Caution**: This will delete all connectors, service configuration, and code templates. Make a backup by installing an "old" version of the microservice first if you don't want to lose them.

**Q: I'm getting an error `Cannot read properties of undefined (reading 'code')` in the browser console after installing a newer version prior to 6.1.0.**

**A:** You need to reinitialize the system code templates: Dynamic Mapper >> Configuration >> Code Template >> Init System Templates
https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases/tag/v6.1.0