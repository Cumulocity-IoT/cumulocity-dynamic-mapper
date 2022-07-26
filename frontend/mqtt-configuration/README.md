# Cumulocity widget plugin

This is the Cumulocity module federation plugin. Plugins can be developed like any Cumulocity application, but can be used at runtime by other applications. Therefore, they export an Angular module which can then be imported by any other application. The exports are defined in `package.json`:

```
"exports": [
  {
     "name": "Example widget plugin",
     "module": "MQTTPluginModule",
     "path": "./widget/widget-plugin.module.ts",
     "description": "Adds custom widget"
  }
]
```

**How to start**
Run the command below to scaffold a `widget` plugin.

```
c8ycli new <yourPluginName> widget-plugin
```

As the app.module is a typical Cumuloctiy application, any new plugin can be tested via the CLI:

```
npm start -- --shell cockpit
```

In the Module Federation terminology, `widget` plugin is called `remote` and the `cokpit` is called `shell`. Modules provided by this `widget` will be loaded by the `cockpit` application at the runtime. This plugin provides a basic custom widget that can be accessed through the `Add widget` menu.

> Note that the `--shell` flag creates a proxy to the cockpit application and provides` MQTTPluginModule` as an `remote` via URL options.

Also deploying needs no special handling and can be simply done via `npm run deploy`. As soon as the application has exports it will be uploaded as a plugin.
