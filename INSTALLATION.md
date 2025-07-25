# Installation Guide

## Prerequisites

To use the mapper you must have the following:

- a Cumulocity Tenant with **microservice** feature subscribed (if not available please ask your contact or support)
- Admin privileges for your user in the tenant

## Installation

You need to install two components to your Cumulocity IoT Tenant:

1. microservice - (Java)
2. Web App Plugin & Blueprint - (angular/Cumulocity WebSDK)

Both are provided as binaries in [releases](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases).
Download the binaries from the latest release and upload them to your Cumulocity IoT Tenant.

> **_NOTE:_**
> The screenshots show the dynamic mapper installed as a plugin in the Cumulocity Administration app. The dynamic mapper can also be installed in other Cumulocity apps, including Cockpit or Device Management, or as a standalone application.

## Permissions

The solution defines one role:`ROLE_MAPPING_ADMIN` that must be assigned to the user accessing the Dynamic Mapper app.

### Microservice

In your Enterprise Tenant or Tenant navigate to "Administration" App, go to "Ecosystem" -> "Microservices" and click on "Add Microservice" on the top right.

Select the `dynamic-mapper-service.zip`.
Make sure that you subscribe the microservice to your tenant when prompted

### Web app

The frontend can be deployed in your tenant:

1. As a **Blueprint** standalone Application selectable from the App switcher

> **_NOTE:_** The option to deploy the dynamic mapper as plugin is deprecated.

#### Community store (Preferred)

The Web App is part of the community store and should be available directly in your tenant under
"Administration" -> "Ecosystem" -> "Extensions" -> "dynamic-mapper". Here you have the choice to install it as a plugin or as a blueprint app.

> **_NOTE:_** The option to deploy the dynamic mapper as plugin is deprecated.
##### ~~Plugin~~

> **_NOTE:_** ~~For a plugin we need to clone the Administration app to add the plugin to i~~

~~Go to "All Applications" and click on "Add Application". Select "Duplicate existing application" and afterward "Administration".~~

<p align="center">
<img src="resources/image/Dynamic_Mapper_DuplicateApp.png" style="width: 40%;" />
</p>
<br/>

~~Now select the cloned Administration App and go to the "Plugin" Tab. Click on "Install Plugin" and select "Dynamic Mapper Widget"~~

<p align="center">
<img src="resources/image/Dynamic_Mapper_Installation_Plugin.png" style="width: 50%;" />
</p>
<br/>

~~After successfully adding the plugin you need to refresh the Administration App by pressing F5 and you should see a new navigation entry "Dynamic Mapper"~~

<p align="center">
 <img src="resources/image/Dynamic_Mapper_WebAppPlugin.png" style="width: 40%;" />
</p>

##### Blueprint

For the blueprint go to "Administration" -> "Ecosystem" -> "dynamic-mapper" -> "Deploy application"

<p align="center">
<img src="resources/image/Dynamic_Mapper_BlueprintDeploy.png" style="width: 50%;" />
</p>

Enter an application key and path and click on "Deploy".

After successful deployment you can find the dynamic-mapper App in the Application Switcher

<p align="center">
<img src="resources/image/Dynamic_Mapper_BlueprintApp.png" style="width: 40%;" />
</p>
#### Manual

If you made changes or your want to upload the plugin manually you can do that by following these steps:

1. In "Administration" App go to "Ecosystem" -> "Packages" and click on "Add Application" on the top right.
2. Select `dynamic-mapper-ui.zip` and wait until it is uploaded.

Follow the steps from the point above to assign the plugin to your Administration App.
