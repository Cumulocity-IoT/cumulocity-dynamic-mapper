# Build & Deploy

## Prerequisites
Make sure that [Docker](https://www.docker.com/), [Apache Maven](https://maven.apache.org/) and [Node.js](https://nodejs.org/) are installed and running on your computer.

## Backend - Microservice
Run `mvn clean package` in folder `dynamic-mapping-service` to build the Microservice which will create a ZIP archive you can upload to Cumulocity.
Just deploy the ZIP to the Cumulocity Tenant like described [here](https://cumulocity.com/guides/users-guide/administration/#uploading-microservices).

## Frontend - App
Run `npm run build` in folder `dynamic-mapping` to build the Front End.
Run `npm run deploy` in folder `dynamic-mapping` to deploy the Front End to your Cumulocity tenant.
The Frontend is build as [Cumulocity plugin](https://cumulocity.com/guides/web/tutorials/#add-a-custom-widget-with-plugin).